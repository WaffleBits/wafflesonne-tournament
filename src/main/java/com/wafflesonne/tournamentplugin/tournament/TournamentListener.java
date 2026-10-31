package com.wafflesonne.tournamentplugin.tournament;

import com.wafflesonne.tournamentplugin.WaffleTournament;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Removes participants who die or disconnect. */
public class TournamentListener implements Listener {

    private final WaffleTournament plugin;

    public TournamentListener(WaffleTournament plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        TournamentManager t = plugin.getTournamentManager();
        if (!t.isParticipant(victim)) return;

        Player killer = victim.getKiller();
        String reason = killer != null
            ? killer.getName() + " tarafindan elendi!"
            : "arena tarafindan yok edildi!";
        t.eliminate(victim, reason);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        TournamentManager t = plugin.getTournamentManager();
        if (!t.isParticipant(event.getPlayer())) return;
        t.eliminate(event.getPlayer(), "sunucudan ayrildi ve elendi.");
    }
}
