package com.wafflesonne.tournamentplugin.command;

import com.wafflesonne.tournamentplugin.WaffleTournament;
import com.wafflesonne.tournamentplugin.activity.ActivityStore;
import com.wafflesonne.tournamentplugin.activity.SlotScorer;
import com.wafflesonne.tournamentplugin.activity.TimeOfWeek;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** All WaffleTournament commands, registered from one place. */
public class EventCommands implements CommandExecutor, TabCompleter {

    private final WaffleTournament plugin;

    public EventCommands(WaffleTournament plugin) {
        this.plugin = plugin;
    }

    public void register() {
        for (String name : List.of("tournament", "starttournament", "stoptournament",
                "turnuva", "wtadmin")) {
            var cmd = plugin.getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        switch (command.getName().toLowerCase()) {
            case "tournament" -> handleTournament(sender, args);
            case "starttournament" -> plugin.getTournamentManager().start(sender);
            case "stoptournament" -> plugin.getTournamentManager().stop(sender);
            case "turnuva" -> handleEvents(sender);
            case "wtadmin" -> handleAdmin(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void handleTournament(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cBu komutu sadece oyuncular kullanabilir.");
            return;
        }
        if (args.length == 0) {
            player.sendMessage("§eKullanim: §a/tournament join §eveya §a/tournament leave");
            return;
        }
        switch (args[0].toLowerCase()) {
            case "join" -> plugin.getTournamentManager().join(player);
            case "leave" -> plugin.getTournamentManager().leave(player);
            default -> player.sendMessage("§eKullanim: §a/tournament join §eveya §a/tournament leave");
        }
    }

    private void handleEvents(CommandSender sender) {
        var t = plugin.getTournamentManager();
        if (t.isActive()) {
            sender.sendMessage("§6[TURNUVA] §eSu anda bir turnuva calisiyor. §7("
                + t.participantCount() + " oyuncu, odul " + t.currentReward() + "$)");
            if (t.isJoinOpen()) sender.sendMessage("§aKatilmak icin /tournament join");
            return;
        }
        if (!plugin.getConfig().getBoolean("schedule.enabled", false)) {
            sender.sendMessage("§eOtomatik turnuva programi su anda kapali.");
            return;
        }
        var scheduler = plugin.getScheduler();
        if (!scheduler.hasCommitment()) {
            sender.sendMessage("§eProgram henuz belirlenmedi.");
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(ActivityStore.ZONE);
        ZonedDateTime next = scheduler.nextStart();

        sender.sendMessage("§6=== Siradaki Turnuva ===");
        sender.sendMessage("§e" + TimeOfWeek.label(scheduler.nextBin())
            + " §7(" + next.toLocalDate() + ")");
        sender.sendMessage("§7Kalan sure: §f"
            + humanise(Duration.between(now, next)));
        sender.sendMessage("§7Tahmini katilim: §f"
            + String.format(Locale.ROOT, "%.1f", scheduler.nextScore()) + " oyuncu");
    }

    private String humanise(Duration d) {
        if (d.isNegative()) return "baslamak uzere";
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long mins = d.toMinutes() % 60;
        if (days > 0) return days + "g " + hours + "s";
        if (hours > 0) return hours + "s " + mins + "dk";
        return mins + "dk";
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("waffletournament.admin")) {
            sender.sendMessage("§cYetkin yok.");
            return;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "status" -> {
                var sch = plugin.getScheduler();
                sender.sendMessage("§6=== WaffleTournament ===");
                sender.sendMessage("§eOtomatik program: §b"
                    + plugin.getConfig().getBoolean("schedule.enabled", true));
                sender.sendMessage("§eModellenen dilim: §b"
                    + plugin.getActivityModel().binsWithData() + "/" + TimeOfWeek.BINS
                    + " §7(" + plugin.getActivityStore().sampleCount() + " olcum)");

                if (!sch.hasCommitment()) {
                    sender.sendMessage("§cKilitli turnuva yok.");
                } else {
                    sender.sendMessage("§eKilitli occurrence:");
                    sender.sendMessage("§f  " + TimeOfWeek.label(sch.nextBin())
                        + "  " + sch.nextStart().toLocalDate()
                        + " " + sch.nextStart().toLocalTime());
                    sender.sendMessage("§eTahmini katilim: §f"
                        + String.format(Locale.ROOT, "%.2f", sch.nextScore()));
                }

                SlotScorer.Ranking r = sch.lastRanking();
                if (r == null) {
                    sender.sendMessage("§7(bu oturumda secim yapilmadi)");
                } else {
                    sender.sendMessage("§ePencere: §f"
                        + r.windowStart().toLocalDate() + " " + r.windowStart().toLocalTime()
                        + " → " + r.windowEnd().toLocalDate() + " " + r.windowEnd().toLocalTime());

                    sender.sendMessage("§eEn iyi uygun adaylar:");
                    int i = 1;
                    for (SlotScorer.Candidate c : r.eligible()) {
                        sender.sendMessage("§7  " + i + ". " + c.label()
                            + " " + c.start().toLocalDate()
                            + ", §a" + String.format(Locale.ROOT, "%.2f", c.score()));
                        if (++i > 3) break;
                    }
                    if (r.eligible().isEmpty()) {
                        sender.sendMessage("§c  (uygun aday yok)");
                    }

                    // Anything that outscored the winner but was ruled out is
                    // the single most useful line here: it shows the filters
                    // are doing something rather than the model being wrong.
                    double winning = r.eligible().isEmpty() ? Double.MAX_VALUE
                        : r.eligible().get(0).score();
                    int shown = 0;
                    for (SlotScorer.Rejected rej : r.rejected()) {
                        if (rej.score() < winning) break;
                        if (shown == 0) sender.sendMessage("§eElenen (daha yuksek skorlu):");
                        sender.sendMessage("§8  " + rej.label()
                            + " " + rej.start().toLocalDate()
                            + ", " + String.format(Locale.ROOT, "%.2f", rej.score())
                            + ", §c" + rej.reason());
                        if (++shown >= 3) break;
                    }
                }
                sender.sendMessage("§eTurnuva aktif: §b"
                    + plugin.getTournamentManager().isActive());
            }
            case "activity" -> {
                ZonedDateTime from = ZonedDateTime.now(ActivityStore.ZONE);
                SlotScorer.Ranking r = plugin.getSlotScorer().rank(from, from.plusDays(9));
                if (r.eligible().isEmpty()) {
                    sender.sendMessage("§eOnumuzdeki 9 gunde uygun aday yok.");
                    return;
                }
                sender.sendMessage("§6=== Onumuzdeki 9 gun, en yuksek skorlar ===");
                r.eligible().stream().limit(10).forEach(x -> sender.sendMessage("§7- §b"
                    + x.label() + " " + x.start().toLocalDate()
                    + " §7skor §a" + String.format(Locale.ROOT, "%.2f", x.score())));
            }
            case "reschedule" -> {
                // Re-picks the upcoming occurrence from the current model. The
                // scheduler never does this on its own between tournaments --
                // that is the point -- so it is an explicit admin action.
                plugin.getScheduler().scheduleNext(ZonedDateTime.now(ActivityStore.ZONE));
                sender.sendMessage("§aYeniden planlandi: §b"
                    + plugin.getScheduler().describeNext());
            }
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage("§aWaffleTournament yeniden yuklendi.");
            }
            default -> sender.sendMessage("§eKullanim: /wtadmin <status|activity|reschedule|reload>");
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        return switch (command.getName().toLowerCase()) {
            case "tournament" -> List.of("join", "leave");
            case "wtadmin" -> List.of("status", "activity", "reschedule", "reload");
            default -> List.of();
        };
    }
}
