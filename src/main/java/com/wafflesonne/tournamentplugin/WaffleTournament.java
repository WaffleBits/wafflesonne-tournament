package com.wafflesonne.tournamentplugin;

import com.wafflesonne.tournamentplugin.activity.ActivitySampler;
import com.wafflesonne.tournamentplugin.activity.ActivityStore;
import com.wafflesonne.tournamentplugin.activity.ActivityModel;
import com.wafflesonne.tournamentplugin.activity.OccurrenceScheduler;
import com.wafflesonne.tournamentplugin.activity.SlotScorer;
import com.wafflesonne.tournamentplugin.command.EventCommands;
import com.wafflesonne.tournamentplugin.tournament.RewardCalculator;
import com.wafflesonne.tournamentplugin.tournament.TournamentListener;
import com.wafflesonne.tournamentplugin.tournament.TournamentManager;
import com.wafflesonne.tournamentplugin.util.Announcer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class WaffleTournament extends JavaPlugin {

    private ActivityStore activityStore;
    private ActivitySampler activitySampler;
    private ActivityModel activityModel;
    private SlotScorer slotScorer;
    private OccurrenceScheduler scheduler;
    private TournamentManager tournamentManager;
    private RewardCalculator rewardCalculator;
    private Announcer announcer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        activityStore = new ActivityStore(this);
        try {
            activityStore.initialize();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not open the activity database", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        activityModel = new ActivityModel(this, activityStore);
        try {
            activityModel.initialize();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not initialise the activity model", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        announcer = new Announcer(this);
        rewardCalculator = new RewardCalculator(this);
        tournamentManager = new TournamentManager(this);
        slotScorer = new SlotScorer(this, activityModel);
        scheduler = new OccurrenceScheduler(this, activityModel, slotScorer);
        activitySampler = new ActivitySampler(this);

        getServer().getPluginManager().registerEvents(new TournamentListener(this), this);
        new EventCommands(this).register();

        activitySampler.start();
        scheduler.initialize();
        scheduler.start();

        getLogger().info("WaffleTournament enabled, "
            + activityStore.sampleCount() + " samples, "
            + activityModel.binsWithData() + "/" + com.wafflesonne.tournamentplugin.activity.TimeOfWeek.BINS
            + " bins modelled, next: " + scheduler.describeNext()
            + (getConfig().getBoolean("schedule.enabled", true)
                ? "" : " (automatic scheduling is OFF)"));
    }

    @Override
    public void onDisable() {
        // Stop sampling first so nothing new lands mid-flush.
        if (activitySampler != null) activitySampler.stop();

        // Fold the part-finished 15-minute bin in and write the model out
        // BEFORE anything else shuts down. This server restarts nightly, so a
        // bin that never naturally transitioned -- observations at 23:45 and
        // 23:50 followed by a stop at 23:55 -- would otherwise be discarded
        // every single night, and the late-evening bins would never learn.
        if (activityModel != null) {
            activityModel.flushPending(System.currentTimeMillis());
            activityModel.save();
        }

        if (scheduler != null) scheduler.stop();

        // Never leave a half-finished round holding the arena door shut.
        if (tournamentManager != null && tournamentManager.isActive()) {
            getLogger().info("Tournament was running at shutdown, resetting arena state");
            tournamentManager.reset();
        }
    }

    public ActivityStore getActivityStore() {
        return activityStore;
    }

    public OccurrenceScheduler getScheduler() {
        return scheduler;
    }

    public ActivityModel getActivityModel() {
        return activityModel;
    }

    public SlotScorer getSlotScorer() {
        return slotScorer;
    }

    public TournamentManager getTournamentManager() {
        return tournamentManager;
    }

    public RewardCalculator getRewardCalculator() {
        return rewardCalculator;
    }

    public Announcer getAnnouncer() {
        return announcer;
    }
}
