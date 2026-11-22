package network.clasher.clashercity.command;

import network.clasher.clashercity.ClasherCityPlugin;
import network.clasher.clashercity.gui.CityGui;
import network.clasher.clashercity.model.Phase;
import network.clasher.clashercity.model.Plot;
import network.clasher.clashercity.storage.CityDatabase;
import network.clasher.clashercity.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Command executor and tab-completer for /clcity, /city, /citymap, /plots, /plot, /buildvote, and /buildtop.
 */
public final class CityCommands implements CommandExecutor, TabCompleter {
    private static final List<String> ADMIN_SUBS = List.of(
            "help", "admin", "create", "tp", "visit", "wand",
            "createplot", "createroad", "createpublic", "setprice", "deleteplot",
            "setspawn", "mapitem", "start", "phase", "voting", "closevote", "end",
            "judge", "score", "winner", "results", "reward", "reload", "info"
    );
    private static final List<String> PLOT_SUBS = List.of("claim", "enter", "home", "info", "abandon");

    private final ClasherCityPlugin plugin;
    private final CityDatabase db;
    private final CityGui gui;

    public CityCommands(ClasherCityPlugin plugin, CityDatabase db, CityGui gui) {
        this.plugin = plugin;
        this.db = db;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "clcity" -> runAdmin(sender, args);
            case "city" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
                if (args.length > 0 && args[0].equalsIgnoreCase("visit")) plugin.teleportCitySpawn(player);
                else gui.openMainMenu(player);
            }
            case "citymap" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
                gui.openMapMenu(player, 0);
            }
            case "plots" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
                gui.openPlotMenu(player, 0);
            }
            case "buildvote" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
                gui.openVoteMenu(player, 0);
            }
            case "buildtop" -> plugin.showResults(sender);
            case "plot" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
                runPlayerPlot(player, args);
            }
            default -> { return false; }
        }
        return true;
    }

    private void runPlayerPlot(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(plugin.msg("<yellow>Usage: /plot <claim|enter|home|info|abandon> [plot-id]</yellow>"));
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "claim" -> {
                if (args.length < 2) player.sendMessage(plugin.msg("<yellow>Usage: /plot claim <plot-id></yellow>"));
                else gui.openPurchaseConfirm(player, args[1]);
            }
            case "enter" -> {
                if (args.length < 2) player.sendMessage(plugin.msg("<yellow>Usage: /plot enter <plot-id></yellow>"));
                else plugin.enterPlot(player, args[1]);
            }
            case "home" -> plugin.teleportHome(player, args.length >= 2 ? args[1] : null);
            case "info" -> {
                if (args.length < 2) player.sendMessage(plugin.msg("<yellow>Usage: /plot info <plot-id></yellow>"));
                else plugin.showPlotInfo(player, args[1]);
            }
            case "abandon" -> {
                if (args.length < 2) player.sendMessage(plugin.msg("<yellow>Usage: /plot abandon <plot-id></yellow>"));
                else plugin.abandonPlot(player, args[1]);
            }
            default -> player.sendMessage(plugin.msg("<yellow>Usage: /plot <claim|enter|home|info|abandon> [plot-id]</yellow>"));
        }
    }

    public void runAdmin(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendAdminHelp(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        // Allow registered judges (or clashercity.judge) to submit scores and view results without full admin!
        if (sub.equals("score") || sub.equals("results")) {
            try {
                boolean allowed = sender.hasPermission("clashercity.admin")
                        || sender.hasPermission("clashercity.judge")
                        || (sender instanceof Player p && db.isJudge(p.getUniqueId()));
                if (!allowed) {
                    sender.sendMessage(plugin.msg("<red>You do not have permission.</red>"));
                    return;
                }
                if (sub.equals("score")) plugin.scoreCommand(sender, args);
                else plugin.showResults(sender);
            } catch (Exception ex) {
                sender.sendMessage(plugin.msg("<red>Operation failed: " + FormatUtil.escape(ex.getMessage()) + "</red>"));
            }
            return;
        }

        if (!sender.hasPermission("clashercity.admin")) {
            sender.sendMessage(plugin.msg("<red>You do not have permission.</red>"));
            return;
        }

        try {
            switch (sub) {
                case "admin" -> {
                    if (sender instanceof Player p) gui.openAdminMenu(p);
                    else sendAdminHelp(sender);
                }
                case "create" -> plugin.ensureCityWorldReady(sender);
                case "tp", "visit" -> {
                    if (sender instanceof Player p) plugin.teleportCitySpawn(p);
                    else sender.sendMessage("Players only.");
                }
                case "wand" -> {
                    if (sender instanceof Player p) plugin.giveSelectionWand(p);
                    else sender.sendMessage("Players only.");
                }
                case "createplot" -> {
                    if (!(sender instanceof Player p) || args.length < 3) {
                        sender.sendMessage(plugin.msg("<yellow>Usage: /clcity createplot <id> <price></yellow>"));
                        return;
                    }
                    plugin.createPlotFromSelection(p, args[1], Double.parseDouble(args[2]));
                }
                case "createroad" -> {
                    if (!(sender instanceof Player p) || args.length < 2) {
                        sender.sendMessage(plugin.msg("<yellow>Usage: /clcity createroad <id></yellow>"));
                        return;
                    }
                    plugin.createAreaFromSelection(p, args[1], "ROAD");
                }
                case "createpublic" -> {
                    if (!(sender instanceof Player p) || args.length < 2) {
                        sender.sendMessage(plugin.msg("<yellow>Usage: /clcity createpublic <id></yellow>"));
                        return;
                    }
                    plugin.createAreaFromSelection(p, args[1], "PUBLIC");
                }
                case "setprice" -> {
                    if (args.length < 3) {
                        sender.sendMessage(plugin.msg("<yellow>Usage: /clcity setprice <plot-id> <price></yellow>"));
                        return;
                    }
                    double price = Double.parseDouble(args[2]);
                    if (price < 0 || !Double.isFinite(price)) throw new IllegalArgumentException("Price must be zero or greater.");
                    sender.sendMessage(plugin.msg(db.updatePlotPrice(args[1], price) ? "<green>Price updated.</green>" : "<red>Plot not found.</red>"));
                }
                case "deleteplot" -> {
                    if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                        sender.sendMessage(plugin.msg("<yellow>Usage: /clcity deleteplot <id> confirm</yellow>"));
                        return;
                    }
                    plugin.deletePlot(args[1]);
                    sender.sendMessage(plugin.msg("<green>Plot deleted: <yellow>" + FormatUtil.escape(args[1]) + "</yellow>.</green>"));
                }
                case "setspawn" -> {
                    if (sender instanceof Player p) plugin.saveCitySpawn(p);
                    else sender.sendMessage("Players only.");
                }
                case "mapitem" -> {
                    if (sender instanceof Player p) plugin.givePhysicalMap(p);
                    else sender.sendMessage("Players only.");
                }
                case "start" -> plugin.startTournament(sender, args.length >= 2
                        ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                        : "ClasherCity Building Championship");
                case "phase" -> {
                    if (args.length < 2) {
                        sender.sendMessage(plugin.msg("<yellow>Usage: /clcity phase <setup|registration|building|voting|results></yellow>"));
                        return;
                    }
                    Phase next = Phase.valueOf(args[1].toUpperCase(Locale.ROOT));
                    db.setPhase(next);
                    plugin.broadcast("<green>Tournament phase changed to <yellow>" + next.name() + "</yellow>.</green>");
                }
                case "voting" -> {
                    db.setPhase(Phase.VOTING);
                    plugin.broadcast("<green>Voting is now open! Use <yellow>/buildvote</yellow>.</green>");
                }
                case "end", "closevote" -> plugin.closeTournament(sender);
                case "judge" -> plugin.judgeCommand(sender, args);
                case "winner" -> plugin.winnerOverrideCommand(sender, args);
                case "reward" -> plugin.rewardCommand(sender, args);
                case "reload" -> {
                    plugin.reloadConfig();
                    sender.sendMessage(plugin.msg("<green>Configuration reloaded.</green>"));
                }
                case "info" -> plugin.showAdminInfo(sender);
                default -> sendAdminHelp(sender);
            }
        } catch (Exception ex) {
            sender.sendMessage(plugin.msg("<red>Operation failed: " + FormatUtil.escape(ex.getMessage()) + "</red>"));
            plugin.getLogger().warning("Admin command failed: " + ex);
        }
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage(FormatUtil.mm("<gold><bold>---- ClasherCity Admin ----</bold></gold>"));
        sender.sendMessage(FormatUtil.mm("<yellow>/clcity admin</yellow> <gray>- Open admin GUI</gray>"));
        sender.sendMessage(FormatUtil.mm("<yellow>/clcity create | tp | setspawn | wand | mapitem</yellow>"));
        sender.sendMessage(FormatUtil.mm("<yellow>/clcity createplot <id> <price> | createroad <id> | createpublic <id></yellow>"));
        sender.sendMessage(FormatUtil.mm("<yellow>/clcity setprice <id> <price> | deleteplot <id> confirm</yellow>"));
        sender.sendMessage(FormatUtil.mm("<yellow>/clcity start [name] | phase <phase> | voting | closevote</yellow>"));
        sender.sendMessage(FormatUtil.mm("<yellow>/clcity judge <add|remove> <player> | score <plot> <category> <marks></yellow>"));
        sender.sendMessage(FormatUtil.mm("<yellow>/clcity winner <1|2|3> <plot|auto> | results</yellow>"));
        sender.sendMessage(FormatUtil.mm("<yellow>/clcity reward <distribute|retry <player>> | reload | info</yellow>"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("city")) {
            if (args.length == 1) options.add("visit");
        } else if (name.equals("plot")) {
            if (args.length == 1) options.addAll(PLOT_SUBS);
            else if (args.length == 2) options.addAll(allPlotIds());
        } else if (name.equals("clcity")) {
            boolean isAdmin = sender.hasPermission("clashercity.admin");
            if (args.length == 1) {
                if (isAdmin) options.addAll(ADMIN_SUBS);
                else if (sender.hasPermission("clashercity.judge")) options.addAll(List.of("score", "results", "help"));
            } else {
                String sub = args[0].toLowerCase(Locale.ROOT);
                if (args.length == 2) {
                    switch (sub) {
                        case "phase" -> { for (Phase p : Phase.values()) options.add(p.name().toLowerCase(Locale.ROOT)); }
                        case "judge" -> options.addAll(List.of("add", "remove"));
                        case "reward" -> options.addAll(List.of("distribute", "retry"));
                        case "winner" -> options.addAll(List.of("1", "2", "3"));
                        case "setprice", "deleteplot", "score" -> options.addAll(allPlotIds());
                        default -> {}
                    }
                } else if (args.length == 3) {
                    switch (sub) {
                        case "deleteplot" -> options.add("confirm");
                        case "judge" -> onlineNames(options);
                        case "reward" -> { if (args[1].equalsIgnoreCase("retry")) onlineNames(options); }
                        case "score" -> options.addAll(plugin.scoreCategories().keySet());
                        case "winner" -> { options.add("auto"); options.addAll(allPlotIds()); }
                        default -> {}
                    }
                }
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }

    private List<String> allPlotIds() {
        try {
            return db.getPlots().stream().map(Plot::id).toList();
        } catch (SQLException ex) {
            return List.of();
        }
    }

    private static void onlineNames(List<String> out) {
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
    }
}
