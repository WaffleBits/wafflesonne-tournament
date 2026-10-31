package com.wafflesonne.tournamentplugin.activity;

import com.wafflesonne.tournamentplugin.WaffleTournament;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Owns the one committed upcoming tournament.
 *
 * <p>The activity model may change at any time; the tournament that has been
 * announced may not. Selection happens once, after the previous tournament
 * runs, and produces an <em>absolute timestamp</em> written to disk. Nothing
 * afterwards recomputes it. The model can decide on Tuesday that Friday looks
 * better; Thursday's tournament still happens on Thursday.
 *
 * <p>Selection searches a window anchored on the previous tournament, by
 * default {@code [T+5d, T+9d]}. That keeps the cadence roughly weekly while
 * leaving the weekday and time free to move: Sunday can be followed by
 * Thursday, then by Friday, then by Wednesday. Enumerating absolute timestamps
 * inside that window, rather than picking a weekday and resolving its next
 * occurrence, is what makes the spacing a guarantee instead of a side effect.
 *
 * <p>Committing to an instant rather than a (day, hour) pair also means a
 * restart cannot lose the schedule, and a restart during the scheduled minute
 * runs the tournament late instead of skipping the week.
 */
public class OccurrenceScheduler {

    private final WaffleTournament plugin;
    private final ActivityModel model;
    private final SlotScorer scorer;
    private final File stateFile;

    private BukkitTask ticker;

    // The committed occurrence.
    private long nextStartMillis;
    private int nextBin = -1;
    private double nextScore;
    private boolean nextHasRun;
    private final List<Integer> announced = new ArrayList<>();

    // Anchor for the next window: when the previous tournament was due.
    private long lastStartMillis;

    // Kept purely for the admin view, so a selection can be audited.
    private SlotScorer.Ranking lastRanking;
    private long lastFailureLogMillis;

    public OccurrenceScheduler(WaffleTournament plugin, ActivityModel model, SlotScorer scorer) {
        this.plugin = plugin;
        this.model = model;
        this.scorer = scorer;
        this.stateFile = new File(plugin.getDataFolder(), "schedule.yml");
    }

    // --- lifecycle ----------------------------------------------------------

    public void initialize() {
        load();
        if (hasCommitment()) {
            plugin.getLogger().info("Restored committed tournament: " + describeNext()
                + ", " + humanise(Duration.between(now(), nextStart())) + " away");
        } else {
            scheduleNext(anchor());
        }
    }

    public void start() {
        if (ticker != null) return;
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 10L, 20L * 30L);
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        save();
    }

    // --- accessors ----------------------------------------------------------

    public boolean hasCommitment() {
        return nextBin >= 0 && nextStartMillis > 0 && !nextHasRun;
    }

    public ZonedDateTime nextStart() {
        return Instant.ofEpochMilli(nextStartMillis).atZone(ActivityStore.ZONE);
    }

    public int nextBin() {
        return nextBin;
    }

    public double nextScore() {
        return nextScore;
    }

    public SlotScorer.Ranking lastRanking() {
        return lastRanking;
    }

    public String describeNext() {
        if (!hasCommitment()) return "none";
        return TimeOfWeek.label(nextBin) + " " + nextStart().toLocalDate()
            + " (tahmini " + String.format(Locale.ROOT, "%.2f", nextScore) + " oyuncu)";
    }

    // --- selection ----------------------------------------------------------

    /** Where the next window is measured from: the previous tournament, or now. */
    private ZonedDateTime anchor() {
        return lastStartMillis > 0
            ? Instant.ofEpochMilli(lastStartMillis).atZone(ActivityStore.ZONE)
            : now();
    }

    /**
     * Pick and commit the next tournament from the current activity model.
     *
     * <p>No hysteresis by design. Stability comes from the long half-life of the
     * estimate and from scoring a window rather than a point, not from refusing
     * to move, the whole purpose is that the time follows the players.
     */
    public void scheduleNext(ZonedDateTime anchor) {
        int minDays = plugin.getConfig().getInt("schedule.next-window-min-days", 5);
        int maxDays = plugin.getConfig().getInt("schedule.next-window-max-days", 9);

        ZonedDateTime now = now();
        ZonedDateTime windowStart = anchor.plusDays(minDays);
        ZonedDateTime windowEnd = anchor.plusDays(maxDays);

        // Long downtime can leave the whole window in the past. Re-anchor on now
        // rather than committing to a timestamp that has already been and gone.
        if (!windowEnd.isAfter(now)) {
            plugin.getLogger().warning("Scheduling window " + windowStart.toLocalDate()
                + " to " + windowEnd.toLocalDate() + " is entirely in the past "
                + "(server down?); re-anchoring on now.");
            windowStart = now.plusDays(minDays);
            windowEnd = now.plusDays(maxDays);
        } else if (windowStart.isBefore(now)) {
            windowStart = now;
        }

        SlotScorer.Ranking ranking = scorer.rank(windowStart, windowEnd);

        if (ranking.isEmpty()) {
            // Explicit fallback: a cold or sparse model is the likely cause, so
            // relax the confidence heuristic but keep the real constraints.
            SlotScorer.Ranking relaxed = scorer.rankIgnoringVisits(windowStart, windowEnd);
            if (!relaxed.isEmpty()) {
                plugin.getLogger().warning("No candidate met schedule.minimum-visits in "
                    + windowStart.toLocalDate() + " to " + windowEnd.toLocalDate()
                    + "; falling back to the best slot regardless of sample count.");
                ranking = relaxed;
            }
        }

        lastRanking = ranking;

        if (ranking.isEmpty()) {
            logNoCandidate(ranking);
            nextBin = -1;
            nextStartMillis = 0;
            nextHasRun = false;
            announced.clear();
            save();
            return;
        }

        SlotScorer.Candidate best = ranking.eligible().get(0);
        nextBin = best.bin();
        nextStartMillis = best.start().toInstant().toEpochMilli();
        nextScore = best.score();
        nextHasRun = false;
        announced.clear();
        save();

        plugin.getLogger().info("Next tournament committed: " + best.label() + " "
            + best.start().toLocalDate() + " " + best.start().toLocalTime()
            + " (predicted " + String.format(Locale.ROOT, "%.2f", best.score())
            + ", window " + windowStart.toLocalDate() + " to " + windowEnd.toLocalDate()
            + ", " + ranking.eligible().size() + " eligible / "
            + ranking.rejected().size() + " rejected)");

        long minutesAway = Duration.between(now, best.start()).toMinutes();
        if (minutesAway <= maxAnnounceThreshold()) {
            announceNow(minutesAway);
        }
    }

    /** Say clearly why nothing could be scheduled, rather than failing quietly. */
    private void logNoCandidate(SlotScorer.Ranking ranking) {
        long nowMillis = System.currentTimeMillis();
        if (nowMillis - lastFailureLogMillis < 3_600_000L) return; // hourly at most
        lastFailureLogMillis = nowMillis;

        Map<String, Integer> byReason = new LinkedHashMap<>();
        for (SlotScorer.Rejected r : ranking.rejected()) {
            byReason.merge(r.reason(), 1, Integer::sum);
        }
        plugin.getLogger().warning("No eligible tournament slot between "
            + ranking.windowStart() + " and " + ranking.windowEnd()
            + ". Rejections: " + (byReason.isEmpty() ? "none (window empty?)" : byReason)
            + ". No tournament is committed, widen schedule.earliest-hour/latest-hour, "
            + "schedule.allowed-weekdays, or schedule.blackouts.");
    }

    // --- tick ---------------------------------------------------------------

    private void tick() {
        if (!plugin.getConfig().getBoolean("schedule.enabled", true)) return;

        ZonedDateTime now = now();
        if (!hasCommitment()) {
            scheduleNext(anchor());
            return;
        }

        long nowMillis = now.toInstant().toEpochMilli();

        if (nowMillis < nextStartMillis) {
            maybeAnnounce((nextStartMillis - nowMillis) / 60_000L);
            return;
        }

        long graceMillis = plugin.getConfig().getInt("schedule.grace-minutes", 90) * 60_000L;
        long lateBy = nowMillis - nextStartMillis;

        if (lateBy > graceMillis) {
            plugin.getLogger().warning("MISSED tournament scheduled for " + nextStart()
                + ", " + (lateBy / 60_000L) + " minutes late, grace window is "
                + (graceMillis / 60_000L) + " minutes. Scheduling the next cycle.");
            markRan();
            scheduleNext(anchor());
            return;
        }

        if (plugin.getTournamentManager().isActive()) {
            return; // one is already running; retry next tick
        }

        plugin.getLogger().info("Starting committed tournament " + TimeOfWeek.label(nextBin)
            + " " + nextStart().toLocalDate()
            + (lateBy >= 60_000L ? " (" + (lateBy / 60_000L) + " min late, within grace)" : ""));

        markRan();
        plugin.getTournamentManager().start(Bukkit.getConsoleSender());

        // Commit the following one immediately, anchored on this occurrence, so
        // there is always exactly one upcoming tournament on record.
        scheduleNext(anchor());
    }

    private void markRan() {
        nextHasRun = true;
        lastStartMillis = nextStartMillis;
        save();
    }

    // --- announcements ------------------------------------------------------

    private List<Integer> announceThresholds() {
        List<Integer> configured =
            plugin.getConfig().getIntegerList("schedule.announce-minutes-before");
        return configured.isEmpty() ? List.of(1440, 60, 10) : configured;
    }

    private int maxAnnounceThreshold() {
        int max = 0;
        for (int t : announceThresholds()) max = Math.max(max, t);
        return max;
    }

    /**
     * Fire each threshold once. Which have already gone out is persisted, so a
     * restart cannot repeat the 24-hour notice every time the server comes up.
     */
    private void maybeAnnounce(long minutesAway) {
        for (int threshold : announceThresholds()) {
            if (minutesAway <= threshold && !announced.contains(threshold)) {
                announced.add(threshold);
                save();
                announceNow(minutesAway);
                return;
            }
        }
    }

    private void announceNow(long minutesAway) {
        String when = minutesAway >= 120
            ? (minutesAway / 60) + " saat"
            : Math.max(0, minutesAway) + " dakika";
        plugin.getAnnouncer().announce("&6[TURNUVA] &eSiradaki turnuva: &b"
            + TimeOfWeek.label(nextBin) + " &7(" + when + " sonra)");
    }

    // --- persistence --------------------------------------------------------

    private void load() {
        if (!stateFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
        nextStartMillis = yaml.getLong("next.start-epoch-millis", 0L);
        nextBin = yaml.getInt("next.bin", -1);
        nextScore = yaml.getDouble("next.score", 0.0);
        nextHasRun = yaml.getBoolean("next.has-run", false);
        lastStartMillis = yaml.getLong("last.start-epoch-millis", 0L);
        announced.clear();
        announced.addAll(yaml.getIntegerList("next.announced"));
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next.start-epoch-millis", nextStartMillis);
        yaml.set("next.bin", nextBin);
        yaml.set("next.label", nextBin >= 0 ? TimeOfWeek.label(nextBin) : "none");
        yaml.set("next.readable", nextStartMillis > 0 ? nextStart().toString() : "none");
        yaml.set("next.score", nextScore);
        yaml.set("next.has-run", nextHasRun);
        yaml.set("next.announced", announced);
        yaml.set("last.start-epoch-millis", lastStartMillis);
        yaml.set("last.readable", lastStartMillis > 0
            ? Instant.ofEpochMilli(lastStartMillis).atZone(ActivityStore.ZONE).toString() : "none");
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create data folder for schedule state");
                return;
            }
            yaml.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                "Could not save schedule state, the committed tournament may be lost "
                    + "on restart", e);
        }
    }

    public static String humanise(Duration d) {
        if (d.isNegative()) return "baslamak uzere";
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long mins = d.toMinutes() % 60;
        if (days > 0) return days + "g " + hours + "s";
        if (hours > 0) return hours + "s " + mins + "dk";
        return mins + "dk";
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ActivityStore.ZONE);
    }
}
