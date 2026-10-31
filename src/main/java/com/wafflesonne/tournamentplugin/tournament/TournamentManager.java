package com.wafflesonne.tournamentplugin.tournament;

import com.wafflesonne.tournamentplugin.WaffleTournament;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The tournament, ported from tournament.sk.
 *
 * <p>Behaviour differences from the Skript version, all deliberate:
 * <ul>
 *   <li>State lives in one object rather than eight global variables, so a
 *       crash or reload cannot leave {@code tournament.in.<uuid>} set forever.</li>
 *   <li>Elimination during the boundary sweep no longer mutates the collection
 *       being iterated, the Skript version called t_check_end() mid-loop.</li>
 *   <li>The plugin disabling mid-round ends the round cleanly instead of
 *       leaving the arena door shut.</li>
 * </ul>
 */
public class TournamentManager {

    private final WaffleTournament plugin;

    private volatile boolean active;
    private volatile boolean joinOpen;
    private volatile boolean started;

    /** Insertion-ordered so the "last player standing" is deterministic. */
    private final Set<UUID> participants = new LinkedHashSet<>();

    private long baseReward;
    private long reward;

    private BukkitTask signupTask;
    private BukkitTask boundaryTask;

    public TournamentManager(WaffleTournament plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isJoinOpen() {
        return joinOpen;
    }

    public int participantCount() {
        return participants.size();
    }

    public long currentReward() {
        return reward;
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    public synchronized boolean start(CommandSender initiator) {
        if (active) {
            initiator.sendMessage("§cZaten bir turnuva calisiyor.");
            return false;
        }
        active = true;
        joinOpen = true;
        started = false;
        participants.clear();

        baseReward = plugin.getRewardCalculator().baseReward();
        reward = plugin.getRewardCalculator().rewardFor(0, baseReward);

        int signup = plugin.getConfig().getInt("tournament.signup-seconds", 120);
        int minPlayers = plugin.getConfig().getInt("tournament.min-players", 3);

        runConfigured("tournament.commands.on-announce", null);
        plugin.getAnnouncer().announce("&6[TURNUVA] &eYeni bir turnuva basliyor!");
        plugin.getAnnouncer().announce("&6[TURNUVA] &eBaslangic odulu: &a" + reward + "$");
        plugin.getAnnouncer().announce("&6[TURNUVA] &eKatilmak icin &a/tournament join &eyaz.");
        plugin.getAnnouncer().announce("&6[TURNUVA] &eSure: &b" + signup
            + " saniye &e| Minimum &b" + minPlayers + " &eoyuncu.");
        playAll(Sound.ITEM_GOAT_HORN_SOUND_6);

        signupTask = Bukkit.getScheduler().runTaskLater(plugin, this::beginRound, signup * 20L);
        scheduleCountdown(signup);
        return true;
    }

    private void scheduleCountdown(int signupSeconds) {
        int[] marks = {60, 30, 10, 5, 4, 3, 2, 1};
        for (int mark : marks) {
            if (mark >= signupSeconds) continue;
            long delay = (signupSeconds - mark) * 20L;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!active || !joinOpen) return;
                if (mark > 5) {
                    plugin.getAnnouncer().announce("&6[TURNUVA] &eBaslamasina &b" + mark + " saniye &ekaldi!");
                } else {
                    plugin.getAnnouncer().broadcastOnly("&6[TURNUVA] &c" + mark + "...");
                }
                playAll(Sound.BLOCK_NOTE_BLOCK_PLING);
            }, delay);
        }
    }

    private synchronized void beginRound() {
        if (!active) return;
        joinOpen = false;

        int minPlayers = plugin.getConfig().getInt("tournament.min-players", 3);
        if (participants.size() < minPlayers) {
            plugin.getAnnouncer().announce("&6[TURNUVA] &cYeterli oyuncu katilmadigi icin turnuva iptal edildi.");
            playAll(Sound.ITEM_GOAT_HORN_SOUND_5);
            reset();
            return;
        }

        plugin.getAnnouncer().announce("&6[TURNUVA] &eTurnuva &b" + participants.size()
            + " &eoyuncu ile basliyor! &7(Odul: &a" + reward + "$&7)");
        playAll(Sound.ITEM_GOAT_HORN_SOUND_1);

        runConfigured("tournament.commands.on-start", null);

        // Give the arena-reset command a moment before teleporting anyone in.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!active) return;
            Arena arena = Arena.fromConfig(plugin);
            if (arena == null) {
                plugin.getLogger().warning("Arena world missing; aborting tournament");
                plugin.getAnnouncer().announce("&6[TURNUVA] &cArena bulunamadi, turnuva iptal edildi.");
                reset();
                return;
            }
            arena.spreadOnRing(onlineParticipants());
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                started = true;
                startBoundarySweep(arena);
            }, 60L);
        }, 20L);
    }

    private void startBoundarySweep(Arena arena) {
        if (boundaryTask != null) boundaryTask.cancel();
        boundaryTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || !started) return;
            // Collect first, mutate after: eliminating inside the iteration is
            // what made the Skript version drop players silently.
            Set<UUID> out = new LinkedHashSet<>();
            for (UUID id : participants) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline()) {
                    out.add(id);
                } else if (!arena.contains(p.getLocation())) {
                    out.add(id);
                }
            }
            for (UUID id : out) {
                Player p = Bukkit.getPlayer(id);
                String name = p != null ? p.getName() : Bukkit.getOfflinePlayer(id).getName();
                participants.remove(id);
                if (p != null && p.isOnline()) {
                    p.sendMessage("§cArena sinirlarini terk ettigin icin turnuvadan elendin!");
                }
                plugin.getAnnouncer().announce("&6[TURNUVA] &e" + name + " &7elendi!");
                playAll(Sound.ITEM_GOAT_HORN_SOUND_4);
            }
            if (!out.isEmpty()) checkEnd();
        }, 20L, 20L);
    }

    // ------------------------------------------------------------------
    // participation
    // ------------------------------------------------------------------

    public synchronized boolean join(Player player) {
        if (!active) {
            player.sendMessage("§cSu anda aktif bir turnuva yok.");
            return false;
        }
        if (!joinOpen) {
            player.sendMessage("§cKatilim suresi bitti.");
            return false;
        }
        if (!participants.add(player.getUniqueId())) {
            player.sendMessage("§eZaten turnuvadasin.");
            return false;
        }
        int min = plugin.getConfig().getInt("tournament.min-players", 3);
        int needed = Math.max(0, min - participants.size());
        plugin.getAnnouncer().announce("&6[TURNUVA] &a" + player.getName()
            + " &eturnuvaya katildi. &7(" + participants.size() + "/" + min + ")"
            + (needed > 0 ? " &8[" + needed + " oyuncu daha gerekli]" : ""));
        playAll(Sound.ITEM_GOAT_HORN_SOUND_0);
        updateReward();
        return true;
    }

    public synchronized boolean leave(Player player) {
        if (!active || !participants.remove(player.getUniqueId())) {
            player.sendMessage("§cTurnuvada degilsin.");
            return false;
        }
        plugin.getAnnouncer().announce("&6[TURNUVA] &e" + player.getName() + " &7turnuvadan ayrildi.");
        checkEnd();
        return true;
    }

    /** Death and quit both remove a player once the round is live. */
    public synchronized void eliminate(Player player, String reason) {
        if (!active || !participants.remove(player.getUniqueId())) return;
        if (joinOpen) {
            // Before the round starts, leaving just cancels the signup.
            plugin.getAnnouncer().announce("&6[TURNUVA] &e" + player.getName() + " &7kaydi iptal edildi.");
            return;
        }
        plugin.getAnnouncer().announce("&6[TURNUVA] &e" + player.getName() + " &7" + reason);
        playAll(Sound.ITEM_GOAT_HORN_SOUND_4);
        checkEnd();
    }

    private void updateReward() {
        reward = plugin.getRewardCalculator().rewardFor(participants.size(), baseReward);
        plugin.getAnnouncer().broadcastOnly("&6[TURNUVA] &eOdul guncellendi: &a" + reward
            + "$ &7(oyuncu: " + participants.size() + ")");
        playAll(Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
    }

    private synchronized void checkEnd() {
        if (!active || joinOpen) return;
        if (participants.size() > 1) return;

        playAll(Sound.ITEM_GOAT_HORN_SOUND_5);
        if (participants.isEmpty()) {
            plugin.getAnnouncer().announce("&6[TURNUVA] &eKimse hayatta kalmadi, kazanan yok!");
            reset();
            return;
        }
        UUID winnerId = participants.iterator().next();
        Player winner = Bukkit.getPlayer(winnerId);
        if (winner == null) {
            plugin.getAnnouncer().announce("&6[TURNUVA] &cKazanan cevrimdisi, odul verilemedi.");
            reset();
            return;
        }
        award(winner);
        reset();
    }

    private void award(Player winner) {
        long amount = Math.max(1, reward);
        plugin.getAnnouncer().announce("&6[TURNUVA] &e" + winner.getName()
            + " &7turnuvanin galibi oldu! &eOdul: &a" + amount + "$");
        winner.sendMessage("§aTurnuvayi kazandin ve " + amount + "$ kazandin!");
        winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        plugin.getRewardCalculator().deposit(winner, amount);

        String group = plugin.getConfig().getString("tournament.winner-group", "sampiyon");
        String duration = plugin.getConfig().getString("tournament.winner-group-duration", "6d23h");
        if (group != null && !group.isBlank()) {
            console("lp user " + winner.getName() + " parent addtemp " + group + " " + duration);
        }
        runConfigured("tournament.commands.on-winner", winner.getName());
    }

    public synchronized void stop(CommandSender by) {
        if (!active) {
            by.sendMessage("§cSu anda aktif bir turnuva yok.");
            return;
        }
        plugin.getAnnouncer().announce("&6[TURNUVA] &cTurnuva durduruldu.");
        playAll(Sound.ITEM_GOAT_HORN_SOUND_5);
        reset();
    }

    /** Clear all state and reopen the arena. Safe to call repeatedly. */
    public synchronized void reset() {
        active = false;
        joinOpen = false;
        started = false;
        participants.clear();
        reward = 0;
        if (signupTask != null) { signupTask.cancel(); signupTask = null; }
        if (boundaryTask != null) { boundaryTask.cancel(); boundaryTask = null; }
        runConfigured("tournament.commands.on-end", null);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private java.util.List<Player> onlineParticipants() {
        java.util.List<Player> out = new java.util.ArrayList<>();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    private void runConfigured(String path, String winner) {
        for (String cmd : plugin.getConfig().getStringList(path)) {
            console(winner == null ? cmd : cmd.replace("{winner}", winner));
        }
    }

    private void console(String command) {
        Runnable r = () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin, r);
    }

    private void playAll(Sound sound) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), sound, 1f, 1f);
        }
    }

    public boolean isParticipant(Player player) {
        return participants.contains(player.getUniqueId());
    }
}
