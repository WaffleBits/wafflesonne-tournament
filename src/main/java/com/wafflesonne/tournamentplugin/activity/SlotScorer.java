package com.wafflesonne.tournamentplugin.activity;

import com.wafflesonne.tournamentplugin.WaffleTournament;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

/**
 * Scores candidate tournament start times inside an absolute window.
 *
 * <p>Candidates are real timestamps, enumerated every 15 minutes across the
 * window, not time-of-week slots whose next occurrence is resolved afterwards.
 * That distinction is what keeps the cadence weekly: picking a weekday bin and
 * then asking when it next occurs can land anywhere from tomorrow to nine days
 * out, so the spacing between tournaments would be whatever the winning bin
 * happened to imply.
 *
 * <p>A start time is judged on the whole stretch it occupies, by default from
 * 30 minutes before (players gathering, seeing the announcement) to 90 minutes
 * after (the rounds playing out), rather than on the single bin it begins in.
 * Weighting that window is what stops one freak 15-minute spike from capturing
 * the schedule: a bin has to sit inside a genuinely busy stretch to win.
 */
public class SlotScorer {

    /** An eligible start time and its predicted attendance. */
    public record Candidate(ZonedDateTime start, int bin, double score) {
        public String label() {
            return TimeOfWeek.label(bin);
        }
    }

    /** A start time that scored but was ruled out, and why. */
    public record Rejected(ZonedDateTime start, int bin, double score, String reason) {
        public String label() {
            return TimeOfWeek.label(bin);
        }
    }

    /** Everything one selection pass looked at. */
    public record Ranking(List<Candidate> eligible, List<Rejected> rejected,
                          ZonedDateTime windowStart, ZonedDateTime windowEnd) {
        public boolean isEmpty() {
            return eligible.isEmpty();
        }
    }

    private final WaffleTournament plugin;
    private final ActivityModel model;

    public SlotScorer(WaffleTournament plugin, ActivityModel model) {
        this.plugin = plugin;
        this.model = model;
    }

    // --- scoring ------------------------------------------------------------

    /** Weighted mean predicted attendance for a tournament starting at this bin. */
    public double score(int startBin) {
        int before = plugin.getConfig().getInt("scoring.window-before-minutes", 30);
        int after = plugin.getConfig().getInt("scoring.window-after-minutes", 90);
        int plateau = plugin.getConfig().getInt("scoring.plateau-minutes", 30);
        double edge = plugin.getConfig().getDouble("scoring.edge-weight", 0.3);
        return weightedMean(model::predict, startBin, before, after, plateau, edge);
    }

    /** The scoring window itself, separate from where its settings come from. */
    static double weightedMean(IntToDoubleFunction predict, int startBin,
                               int before, int after, int plateau, double edge) {
        double weighted = 0;
        double totalWeight = 0;

        int firstOffset = -(before / TimeOfWeek.MINUTES_PER_BIN);
        int lastOffset = after / TimeOfWeek.MINUTES_PER_BIN;

        for (int offset = firstOffset; offset <= lastOffset; offset++) {
            int minutes = offset * TimeOfWeek.MINUTES_PER_BIN;
            double w = weightAt(minutes, before, after, plateau, edge);
            weighted += predict.applyAsDouble(startBin + offset) * w;
            totalWeight += w;
        }
        return totalWeight == 0 ? 0 : weighted / totalWeight;
    }

    static double weightAt(int minutes, int before, int after, int plateau, double edge) {
        if (minutes < 0) {
            double t = before == 0 ? 1.0 : (minutes + before) / (double) before;
            return edge + (1.0 - edge) * clamp01(t);
        }
        if (minutes <= plateau) {
            return 1.0;
        }
        double span = Math.max(1, after - plateau);
        return 1.0 - (1.0 - edge) * clamp01((minutes - plateau) / (double) span);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    // --- candidate enumeration ---------------------------------------------

    /**
     * Score every 15-minute start time in {@code [windowStart, windowEnd]}.
     *
     * <p>Rejected candidates are kept with their score and the rule that
     * excluded them, so the admin view can show that e.g. a higher-scoring
     * 23:15 slot lost to a lower-scoring 21:30 only because of the restart
     * blackout, otherwise there is no way to tell a working scheduler from a
     * broken one by looking at its output.
     */
    public Ranking rank(ZonedDateTime windowStart, ZonedDateTime windowEnd) {
        int earliest = plugin.getConfig().getInt("schedule.earliest-hour", 12);
        int latest = plugin.getConfig().getInt("schedule.latest-hour", 22);
        List<String> allowedDays = plugin.getConfig().getStringList("schedule.allowed-weekdays");
        long minVisits = plugin.getConfig().getLong("schedule.minimum-visits", 2);

        List<Candidate> eligible = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();

        for (ZonedDateTime t = alignUp(windowStart); !t.isAfter(windowEnd);
             t = t.plusMinutes(TimeOfWeek.MINUTES_PER_BIN)) {

            int bin = TimeOfWeek.binOf(t);
            double score = score(bin);

            String reason = rejectionReason(t, bin, earliest, latest, allowedDays, minVisits);
            if (reason == null) {
                eligible.add(new Candidate(t, bin, score));
            } else {
                rejected.add(new Rejected(t, bin, score, reason));
            }
        }

        eligible.sort(Comparator.comparingDouble(Candidate::score).reversed()
            .thenComparing(Candidate::start));
        rejected.sort(Comparator.comparingDouble(Rejected::score).reversed());

        return new Ranking(eligible, rejected, windowStart, windowEnd);
    }

    /**
     * Same window, but ignoring the minimum-visits requirement.
     *
     * <p>Used only as an explicit fallback when a cold or sparse model would
     * otherwise leave the window with no eligible candidate at all. Hour,
     * weekday and blackout rules still apply, those are real constraints,
     * whereas minimum-visits is a data-confidence heuristic.
     */
    public Ranking rankIgnoringVisits(ZonedDateTime windowStart, ZonedDateTime windowEnd) {
        long saved = plugin.getConfig().getLong("schedule.minimum-visits", 2);
        plugin.getConfig().set("schedule.minimum-visits", 0L);
        try {
            return rank(windowStart, windowEnd);
        } finally {
            plugin.getConfig().set("schedule.minimum-visits", saved);
        }
    }

    private String rejectionReason(ZonedDateTime t, int bin, int earliest, int latest,
                                   List<String> allowedDays, long minVisits) {
        int hour = t.getHour();
        if (hour < earliest || hour > latest) return "HOURS";
        if (!dayAllowed(allowedDays, t)) return "WEEKDAY";
        if (inBlackout(t)) return "BLACKOUT";
        if (model.visits(bin) < minVisits) return "NO DATA";
        return null;
    }

    /** Round up to the next 15-minute boundary so candidates land on bin starts. */
    private ZonedDateTime alignUp(ZonedDateTime t) {
        ZonedDateTime floor = t.withMinute((t.getMinute() / TimeOfWeek.MINUTES_PER_BIN)
                * TimeOfWeek.MINUTES_PER_BIN)
            .withSecond(0).withNano(0);
        return floor.isBefore(t) ? floor.plusMinutes(TimeOfWeek.MINUTES_PER_BIN) : floor;
    }

    private boolean dayAllowed(List<String> allowed, ZonedDateTime t) {
        if (allowed == null || allowed.isEmpty()) return true;
        String name = t.getDayOfWeek().name();
        for (String s : allowed) {
            if (s != null && s.trim().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Keep tournaments clear of the nightly restart and any other maintenance
     * window.
     *
     * <p>Purely a scheduling constraint: the activity model still learns from
     * these times, because players really are online then. It just may not
     * start an event that would be interrupted partway through.
     *
     * <pre>
     * schedule:
     *   blackouts:
     *     - start: "23:00"
     *       end:   "00:30"
     * </pre>
     */
    private boolean inBlackout(ZonedDateTime t) {
        List<Map<?, ?>> windows = plugin.getConfig().getMapList("schedule.blackouts");
        if (windows == null || windows.isEmpty()) return false;

        LocalTime at = t.toLocalTime();
        for (Map<?, ?> window : windows) {
            LocalTime from = parseTime(window.get("start"));
            LocalTime to = parseTime(window.get("end"));
            if (from == null || to == null) continue;

            // An end earlier than the start means the window wraps midnight.
            boolean hit = from.isBefore(to)
                ? (!at.isBefore(from) && at.isBefore(to))
                : (!at.isBefore(from) || at.isBefore(to));
            if (hit) return true;
        }
        return false;
    }

    private LocalTime parseTime(Object value) {
        if (value == null) return null;
        try {
            String[] hm = value.toString().trim().split(":");
            return LocalTime.of(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
        } catch (Exception e) {
            plugin.getLogger().warning("Unparseable blackout time: " + value);
            return null;
        }
    }
}
