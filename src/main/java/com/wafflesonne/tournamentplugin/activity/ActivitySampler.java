package com.wafflesonne.tournamentplugin.activity;

import com.wafflesonne.tournamentplugin.WaffleTournament;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Periodically records how many players are online.
 *
 * <p>Counting happens on the main thread (Bukkit state) but the write goes to
 * the async executor, so the sampler never blocks a tick on SQLite.
 */
public class ActivitySampler {

    private final WaffleTournament plugin;
    private BukkitTask task;

    public ActivitySampler(WaffleTournament plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) return;
        long period = Math.max(1, plugin.getConfig().getInt("activity.sample-minutes", 5)) * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 20L * 30L, period);
        plugin.getLogger().info("Activity sampler started (every "
            + plugin.getConfig().getInt("activity.sample-minutes", 5) + " min)");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void sample() {
        // Vanished staff are genuinely present but invisible; counting them would
        // make quiet hours look busier than a player experiences them.
        int online = 0;
        for (var p : Bukkit.getOnlinePlayers()) {
            if (isVanished(p)) continue;
            online++;
        }
        final int count = online;
        final long nowMillis = System.currentTimeMillis();

        if (excluded(nowMillis)) {
            // A tournament pulls players in, so counting during one would teach
            // the model that the slot it already chose is the busiest, the
            // schedule would then never leave it, whatever players actually do.
            plugin.getActivityModel().discardPending();
            return;
        }

        plugin.getActivityModel().observe(nowMillis, count);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
            plugin.getActivityStore().addSample(nowMillis / 1000L, count));
    }

    /** True while a tournament is running, or shortly after one finished. */
    private boolean excluded(long nowMillis) {
        if (plugin.getTournamentManager() != null && plugin.getTournamentManager().isActive()) {
            lastTournamentMillis = nowMillis;
            return true;
        }
        int cooldown = plugin.getConfig().getInt("activity.exclude-around-tournament-minutes", 60);
        return lastTournamentMillis > 0
            && (nowMillis - lastTournamentMillis) < cooldown * 60_000L;
    }

    private long lastTournamentMillis;

    private boolean isVanished(org.bukkit.entity.Player player) {
        for (var meta : player.getMetadata("vanished")) {
            if (meta.asBoolean()) return true;
        }
        return false;
    }
}
