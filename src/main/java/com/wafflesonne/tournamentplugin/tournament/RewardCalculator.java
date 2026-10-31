package com.wafflesonne.tournamentplugin.tournament;

import com.wafflesonne.tournamentplugin.WaffleTournament;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Prize pool, ported from tournament.sk's t_calc_reward / t_reward_for_count.
 *
 * <p>Base reward is 10% of the median of the top-10 balances, so the pot tracks
 * the economy instead of being a fixed number that inflates into irrelevance.
 * The payout then scales from 0.1x at the minimum player count to 1.0x at the
 * server slot limit along a convex curve, so each additional entrant is worth
 * more than the previous one.
 */
public class RewardCalculator {

    private final WaffleTournament plugin;

    public RewardCalculator(WaffleTournament plugin) {
        this.plugin = plugin;
    }

    public long baseReward() {
        List<Double> balances = new ArrayList<>();
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            for (int rank = 1; rank <= 10; rank++) {
                Double v = parseBaltop(rank);
                if (v != null) balances.add(v);
            }
        }
        long fallback = plugin.getConfig().getLong("tournament.fallback-reward", 5000);
        if (balances.isEmpty()) {
            return Math.max(1, (long) Math.floor(fallback * 0.1));
        }
        Collections.sort(balances);
        double median = balances.get(balances.size() / 2);
        return Math.max(1, (long) Math.floor(median * 0.1));
    }

    private Double parseBaltop(int rank) {
        try {
            String raw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                null, "%essentials_baltop_balance_" + rank + "%");
            if (raw == null || raw.isBlank() || raw.startsWith("%")) return null;
            raw = raw.replace(",", "").replace("$", "").replace(" ", "").trim();
            return Double.parseDouble(raw);
        } catch (Throwable ignored) {
            // PlaceholderAPI absent, expansion missing, or unparseable, treat as no data.
            return null;
        }
    }

    /** Payout for a given entrant count. */
    public long rewardFor(int count, long base) {
        int min = plugin.getConfig().getInt("tournament.min-players", 3);
        int max = Bukkit.getMaxPlayers();
        double exponent = plugin.getConfig().getDouble("tournament.reward-curve-exponent", 2.0);

        return Math.max(1, (long) Math.floor(base * payoutMultiplier(count, min, max, exponent)));
    }

    /** 0.1x at the minimum entrant count, 1.0x at the slot limit, convex between. */
    static double payoutMultiplier(int count, int min, int max, double exponent) {
        if (max <= min) return 0.1;
        int p = Math.min(Math.max(count, min), max);
        double t = (double) (p - min) / (max - min);
        return 0.1 + 0.9 * Math.pow(t, exponent);
    }

    /** Pay the winner through Vault if present, otherwise via the Essentials command. */
    public void deposit(Player player, long amount) {
        try {
            Class.forName("net.milkbowl.vault.economy.Economy");
            var rsp = Bukkit.getServicesManager().getRegistration(
                net.milkbowl.vault.economy.Economy.class);
            if (rsp != null) {
                rsp.getProvider().depositPlayer(player, amount);
                return;
            }
        } catch (Throwable ignored) {
            // Vault not installed or not shaded in, fall through.
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "eco give " + player.getName() + " " + amount);
    }
}
