package com.wafflesonne.tournamentplugin.util;

import com.wafflesonne.tournamentplugin.WaffleTournament;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

/**
 * Sends an announcement in-game and to Discord.
 *
 * <p>Discord delivery goes through DiscordSRV's {@code discord bcast} console
 * command rather than a compile-time dependency: it is what the server already
 * uses, it needs no bot token of our own, and it degrades to a logged warning
 * if DiscordSRV is absent.
 */
public class Announcer {

    private final WaffleTournament plugin;

    public Announcer(WaffleTournament plugin) {
        this.plugin = plugin;
    }

    /** Broadcast in-game and mirror to the tournament Discord channel. */
    public void announce(String coloured) {
        String msg = ChatColor.translateAlternateColorCodes('&', coloured);
        Bukkit.broadcastMessage(msg);
        toDiscord(plugin.getConfig().getString("discord.tournament-channel", ""),
            ChatColor.stripColor(msg));
    }

    /** In-game only, for chatter that should not spam Discord. */
    public void broadcastOnly(String coloured) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', coloured));
    }

    public void debug(String plain) {
        toDiscord(plugin.getConfig().getString("discord.debug-channel", ""), plain);
    }

    private void toDiscord(String channelId, String plain) {
        if (!plugin.getConfig().getBoolean("discord.enabled", true)) return;
        if (channelId == null || channelId.isBlank()) return;
        if (Bukkit.getPluginManager().getPlugin("DiscordSRV") == null) {
            plugin.getLogger().fine("DiscordSRV not present; dropped announcement");
            return;
        }
        // Console dispatch must happen on the main thread.
        Runnable dispatch = () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "discord bcast #" + channelId + " " + plain);
        if (Bukkit.isPrimaryThread()) {
            dispatch.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, dispatch);
        }
    }
}
