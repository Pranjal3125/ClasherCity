package network.clasher.clashercity.gui;

import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import network.clasher.clashercity.ClasherCityPlugin;
import network.clasher.clashercity.model.Phase;
import network.clasher.clashercity.model.Plot;
import network.clasher.clashercity.storage.CityDatabase;
import network.clasher.clashercity.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paginated, InventoryHolder-secured Adventure MiniMessage GUIs for ClasherCity.
 */
public final class CityGui implements Listener {
    private final ClasherCityPlugin plugin;
    private final CityDatabase db;
    private final Economy economy;
    private final NamespacedKey keyAction;
    private final NamespacedKey keyPlot;
    private final Map<UUID, Long> lastGuiAction = new HashMap<>();

    public CityGui(ClasherCityPlugin plugin, CityDatabase db, Economy economy) {
        this.plugin = plugin;
        this.db = db;
        this.economy = economy;
        this.keyAction = new NamespacedKey(plugin, "action");
        this.keyPlot = new NamespacedKey(plugin, "plot_id");
    }

    public void openMainMenu(Player player) {
        CityMenuHolder holder = new CityMenuHolder(CityMenuHolder.MenuType.MAIN, 0);
        Inventory inv = Bukkit.createInventory(holder, 27, FormatUtil.mm("<dark_gray><bold>ClasherCity</bold></dark_gray> <gray>|</gray> <gold>Main Menu</gold>"));
        holder.setInventory(inv);
        fillBorder(inv, 27);

        inv.setItem(8, item(Material.COMPASS, "<aqua><bold>Visit City</bold></aqua>", List.of("<gray>Teleport to ClasherCity spawn</gray>"), "visit", null));
        inv.setItem(10, item(Material.LIME_CONCRETE, "<green><bold>Plot Marketplace</bold></green>", List.of("<gray>Browse available city plots</gray>", "<yellow>Click to open</yellow>"), "plots", null));
        inv.setItem(12, item(Material.FILLED_MAP, "<aqua><bold>City Map</bold></aqua>", List.of("<gray>View plot availability</gray>"), "map", null));
        inv.setItem(14, item(Material.OAK_DOOR, "<yellow><bold>My Plot</bold></yellow>", List.of("<gray>Teleport to your first owned plot</gray>"), "home", null));
        inv.setItem(16, item(Material.WRITABLE_BOOK, "<light_purple><bold>Tournament & Voting</bold></light_purple>", List.of("<gray>Phase:</gray> <white>" + db.phase() + "</white>", "<gray>Vote during the VOTING phase</gray>"), "vote", null));
        inv.setItem(22, item(Material.GOLD_INGOT, "<gold><bold>Leaderboard</bold></gold>", List.of("<gray>View current tournament standings</gray>"), "top", null));
        player.openInventory(inv);
    }

    public void openAdminMenu(Player player) {
        CityMenuHolder holder = new CityMenuHolder(CityMenuHolder.MenuType.ADMIN, 0);
        Inventory inv = Bukkit.createInventory(holder, 27, FormatUtil.mm("<dark_gray><bold>ClasherCity</bold></dark_gray> <gray>|</gray> <red>Admin</red>"));
        holder.setInventory(inv);
        fillBorder(inv, 27);

        inv.setItem(10, item(Material.GRASS_BLOCK, "<green>Load/Create City World</green>", List.of("<gray>/clcity create</gray>"), "admin_create", null));
        inv.setItem(11, item(Material.BLAZE_ROD, "<yellow>Selection Wand</yellow>", List.of("<gray>/clcity wand</gray>"), "admin_wand", null));
        inv.setItem(12, item(Material.MAP, "<aqua>Give Physical Map</aqua>", List.of("<gray>/clcity mapitem</gray>"), "admin_map", null));
        inv.setItem(14, item(Material.EMERALD, "<green>Start Tournament</green>", List.of("<gray>Opens plot registration</gray>"), "admin_start", null));
        inv.setItem(15, item(Material.CLOCK, "<yellow>Set BUILDING Phase</yellow>", List.of("<gray>Allows plot owners to build</gray>"), "admin_building", null));
        inv.setItem(16, item(Material.BOOK, "<light_purple>Open Voting</light_purple>", List.of("<gray>Opens /buildvote</gray>"), "admin_voting", null));
        inv.setItem(22, item(Material.GOLD_BLOCK, "<gold>Show Results</gold>", List.of("<gray>/buildtop</gray>"), "admin_results", null));
        player.openInventory(inv);
    }

    public void openPlotMenu(Player player, int page) {
        List<Plot> plots;
        try {
            plots = db.getPlots();
        } catch (SQLException ex) {
            player.sendMessage(plugin.msg("<red>Could not load plots.</red>"));
            return;
        }
        int maxPage = Math.max(0, (plots.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));
        CityMenuHolder holder = new CityMenuHolder(CityMenuHolder.MenuType.PLOTS, page);
        Inventory inv = Bukkit.createInventory(holder, 54, FormatUtil.mm("<dark_gray>ClasherCity | Plots</dark_gray> <gold>" + (page + 1) + "/" + (maxPage + 1) + "</gold>"));
        holder.setInventory(inv);

        int start = page * 45;
        for (int i = 0; i < 45 && start + i < plots.size(); i++) {
            Plot p = plots.get(start + i);
            boolean sold = p.isOwned();
            inv.setItem(i, item(
                    sold ? Material.RED_CONCRETE : Material.LIME_CONCRETE,
                    (sold ? "<red>" : "<green>") + FormatUtil.escape(p.id()) + (sold ? "</red>" : "</green>"),
                    List.of(
                            "<gray>Size:</gray> <white>" + p.width() + " × " + p.depth() + "</white>",
                            "<gray>Price:</gray> <yellow>" + FormatUtil.escape(economy.format(p.price())) + "</yellow>",
                            sold ? "<gray>Owner:</gray> <white>" + FormatUtil.escape(p.ownerName()) + "</white>" : "<green>Available</green>",
                            "",
                            "<yellow>Click for details</yellow>"
                    ),
                    "plot_info",
                    p.id()
            ));
        }
        if (page > 0) inv.setItem(45, item(Material.ARROW, "<yellow>Previous Page</yellow>", List.of(), "prev_plots", null));
        inv.setItem(49, item(Material.MAP, "<aqua>City Map</aqua>", List.of("<gray>Switch to map view</gray>"), "map", null));
        if (page < maxPage) inv.setItem(53, item(Material.ARROW, "<yellow>Next Page</yellow>", List.of(), "next_plots", null));
        player.openInventory(inv);
    }

    public void openPlotInfoMenu(Player player, String id) {
        try {
            Plot p = db.getPlot(id);
            if (p == null) {
                player.sendMessage(plugin.msg("<red>Plot not found.</red>"));
                return;
            }
            CityMenuHolder holder = new CityMenuHolder(CityMenuHolder.MenuType.PLOT_INFO, 0);
            Inventory inv = Bukkit.createInventory(holder, 27, FormatUtil.mm("<dark_gray>Plot</dark_gray> <gold>" + FormatUtil.escape(p.id()) + "</gold>"));
            holder.setInventory(inv);
            fillBorder(inv, 27);

            boolean sold = p.isOwned();
            inv.setItem(13, item(
                    sold ? Material.RED_CONCRETE : Material.LIME_CONCRETE,
                    (sold ? "<red>" : "<green>") + FormatUtil.escape(p.id()) + (sold ? "</red>" : "</green>"),
                    List.of(
                            "<gray>Dimensions:</gray> <white>" + p.width() + " × " + p.depth() + "</white>",
                            "<gray>Price:</gray> <yellow>" + FormatUtil.escape(economy.format(p.price())) + "</yellow>",
                            "<gray>Status:</gray> " + (sold ? "<red>Sold to " + FormatUtil.escape(p.ownerName()) + "</red>" : "<green>Available</green>")
                    ),
                    "none",
                    p.id()
            ));
            if (!sold && db.phase() == Phase.REGISTRATION) {
                inv.setItem(11, item(
                        Material.LIME_CONCRETE,
                        "<green><bold>Purchase Plot</bold></green>",
                        List.of("<gray>Confirm in the next screen</gray>", "<gray>Price:</gray> <yellow>" + FormatUtil.escape(economy.format(p.price())) + "</yellow>"),
                        "confirm",
                        p.id()
                ));
            }
            if (sold && db.phase() == Phase.REGISTRATION && p.ownerUuid().equals(player.getUniqueId().toString())) {
                boolean entered = db.isTournamentEntry(p.id());
                inv.setItem(11, item(
                        entered ? Material.LIME_DYE : Material.WRITABLE_BOOK,
                        entered ? "<green>Already Entered</green>" : "<yellow><bold>Enter This Plot</bold></yellow>",
                        List.of("<gray>Register your owned plot for this tournament</gray>"),
                        entered ? "none" : "enter",
                        p.id()
                ));
            }
            inv.setItem(15, item(Material.ENDER_PEARL, "<aqua>Teleport to Plot</aqua>", List.of("<gray>Visit this plot in the city</gray>"), "visit_plot", p.id()));
            inv.setItem(22, item(Material.ARROW, "<yellow>Back to Plots</yellow>", List.of(), "plots", null));
            player.openInventory(inv);
        } catch (SQLException ex) {
            player.sendMessage(plugin.msg("<red>Database error.</red>"));
        }
    }

    public void openPurchaseConfirm(Player player, String id) {
        Plot plot;
        try {
            plot = db.getPlot(id);
        } catch (SQLException ex) {
            player.sendMessage(plugin.msg("<red>Database error.</red>"));
            return;
        }
        if (plot == null) { player.sendMessage(plugin.msg("<red>Plot not found.</red>")); return; }
        if (plot.isOwned()) { player.sendMessage(plugin.msg("<red>That plot is already owned by " + FormatUtil.escape(plot.ownerName()) + ".</red>")); return; }
        if (db.phase() != Phase.REGISTRATION) { player.sendMessage(plugin.msg("<red>Plot purchases are only open during registration.</red>")); return; }

        double balance = economy.getBalance(player);
        double after = Math.max(0.0, balance - plot.price());
        CityMenuHolder holder = new CityMenuHolder(CityMenuHolder.MenuType.CONFIRM, 0);
        Inventory inv = Bukkit.createInventory(holder, 27, FormatUtil.mm("<dark_gray>Confirm Plot Purchase</dark_gray>"));
        holder.setInventory(inv);
        fillBorder(inv, 27);

        inv.setItem(11, item(
                Material.LIME_CONCRETE,
                "<green><bold>Confirm Purchase</bold></green>",
                List.of(
                        "<gray>Plot:</gray> <white>" + FormatUtil.escape(plot.id()) + "</white>",
                        "<gray>Price:</gray> <yellow>" + FormatUtil.escape(economy.format(plot.price())) + "</yellow>",
                        "<gray>Balance:</gray> <white>" + FormatUtil.escape(economy.format(balance)) + "</white>",
                        "<gray>After Purchase:</gray> <aqua>" + FormatUtil.escape(economy.format(after)) + "</aqua>"
                ),
                "buy",
                plot.id()
        ));
        inv.setItem(13, item(
                Material.MAP,
                "<yellow><bold>Plot " + FormatUtil.escape(plot.id()) + "</bold></yellow>",
                List.of("<gray>Size:</gray> <white>" + plot.width() + " × " + plot.depth() + "</white>", "<gray>Price:</gray> <yellow>" + FormatUtil.escape(economy.format(plot.price())) + "</yellow>"),
                "none",
                plot.id()
        ));
        inv.setItem(15, item(Material.RED_CONCRETE, "<red><bold>Cancel</bold></red>", List.of("<gray>Return to plot browser</gray>"), "plots", null));
        player.openInventory(inv);
    }

    public void openMapMenu(Player player, int page) {
        List<Plot> plots;
        try {
            plots = db.getPlots();
        } catch (SQLException ex) {
            player.sendMessage(plugin.msg("<red>Could not load map.</red>"));
            return;
        }
        int perPage = 36;
        int maxPage = Math.max(0, (plots.size() - 1) / perPage);
        page = Math.max(0, Math.min(page, maxPage));
        CityMenuHolder holder = new CityMenuHolder(CityMenuHolder.MenuType.MAP, page);
        Inventory inv = Bukkit.createInventory(holder, 54, FormatUtil.mm("<dark_gray>ClasherCity | Map</dark_gray> <gold>" + (page + 1) + "/" + (maxPage + 1) + "</gold>"));
        holder.setInventory(inv);

        inv.setItem(4, item(Material.FILLED_MAP, "<aqua><bold>City Overview</bold></aqua>",
                List.of("<green>Green:</green> <gray>Available</gray>", "<red>Red:</red> <gray>Purchased</gray>", "<gray>Grey: Roads | Blue: Public areas</gray>"),
                "none", null));

        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < plots.size(); i++) {
            Plot p = plots.get(start + i);
            boolean sold = p.isOwned();
            inv.setItem(i + 9, item(
                    sold ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                    (sold ? "<red>" : "<green>") + FormatUtil.escape(p.id()) + (sold ? "</red>" : "</green>"),
                    List.of(
                            "<gray>Price:</gray> <yellow>" + FormatUtil.escape(economy.format(p.price())) + "</yellow>",
                            sold ? "<gray>Owner:</gray> <white>" + FormatUtil.escape(p.ownerName()) + "</white>" : "<green>Available</green>"
                    ),
                    "plot_info",
                    p.id()
            ));
        }
        if (page > 0) inv.setItem(45, item(Material.ARROW, "<yellow>Previous Page</yellow>", List.of(), "prev_map", null));
        inv.setItem(49, item(Material.PAPER, "<yellow>Physical Map Item</yellow>", List.of("<gray>Admins can spawn one via /clcity mapitem</gray>"), "none", null));
        if (page < maxPage) inv.setItem(53, item(Material.ARROW, "<yellow>Next Page</yellow>", List.of(), "next_map", null));
        player.openInventory(inv);
    }

    public void openVoteMenu(Player player, int page) {
        if (db.phase() != Phase.VOTING) {
            player.sendMessage(plugin.msg("<red>Voting is not open right now.</red>"));
            return;
        }
        if (!plugin.isEligibleVoter(player)) {
            player.sendMessage(plugin.msg("<red>You do not meet the minimum playtime requirement.</red>"));
            return;
        }
        List<Plot> eligible = new ArrayList<>();
        try {
            for (Plot p : db.getTournamentPlots()) {
                if (!p.ownerUuid().equals(player.getUniqueId().toString())) eligible.add(p);
            }
        } catch (SQLException ex) {
            player.sendMessage(plugin.msg("<red>Could not load tournament entries.</red>"));
            return;
        }

        int maxPage = Math.max(0, (eligible.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));
        CityMenuHolder holder = new CityMenuHolder(CityMenuHolder.MenuType.VOTE, page);
        Inventory inv = Bukkit.createInventory(holder, 54, FormatUtil.mm("<dark_gray>ClasherCity | Vote</dark_gray> <gold>" + (page + 1) + "/" + (maxPage + 1) + "</gold>"));
        holder.setInventory(inv);

        int start = page * 45;
        for (int i = 0; i < 45 && start + i < eligible.size(); i++) {
            Plot p = eligible.get(start + i);
            inv.setItem(i, item(
                    Material.PAPER,
                    "<yellow>" + FormatUtil.escape(p.id()) + "</yellow> <gray>-</gray> <white>" + FormatUtil.escape(p.ownerName()) + "</white>",
                    List.of(
                            "<green>Left-Click:</green> <gray>Cast your vote for this build</gray>",
                            "<aqua>Right-Click:</aqua> <gray>Teleport to inspect this plot</gray>",
                            "<dark_gray>One vote per player per tournament.</dark_gray>"
                    ),
                    "vote_entry",
                    p.id()
            ));
        }
        if (eligible.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, "<red>No Eligible Entries</red>", List.of("<gray>Other players must enter plots first.</gray>"), "none", null));
        }
        if (page > 0) inv.setItem(45, item(Material.ARROW, "<yellow>Previous Page</yellow>", List.of(), "prev_vote", null));
        if (page < maxPage) inv.setItem(53, item(Material.ARROW, "<yellow>Next Page</yellow>", List.of(), "next_vote", null));
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof CityMenuHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;

        long now = System.currentTimeMillis();
        long prev = lastGuiAction.getOrDefault(player.getUniqueId(), 0L);
        if (now - prev < 150L) return;
        lastGuiAction.put(player.getUniqueId(), now);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;
        String action = meta.getPersistentDataContainer().get(keyAction, PersistentDataType.STRING);
        String plotId = meta.getPersistentDataContainer().get(keyPlot, PersistentDataType.STRING);
        if (action == null || action.equals("none")) return;

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        switch (action) {
            case "plots" -> openPlotMenu(player, 0);
            case "map" -> openMapMenu(player, 0);
            case "home" -> { player.closeInventory(); plugin.teleportHome(player, null); }
            case "visit" -> { player.closeInventory(); plugin.teleportCitySpawn(player); }
            case "visit_plot" -> {
                player.closeInventory();
                plugin.teleportToPlotById(player, plotId);
            }
            case "vote" -> openVoteMenu(player, 0);
            case "vote_entry" -> {
                if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT) {
                    player.closeInventory();
                    plugin.teleportToPlotById(player, plotId);
                } else {
                    plugin.castVote(player, plotId);
                }
            }
            case "top" -> { player.closeInventory(); plugin.showResults(player); }
            case "prev_plots" -> openPlotMenu(player, holder.page() - 1);
            case "next_plots" -> openPlotMenu(player, holder.page() + 1);
            case "prev_map" -> openMapMenu(player, holder.page() - 1);
            case "next_map" -> openMapMenu(player, holder.page() + 1);
            case "prev_vote" -> openVoteMenu(player, holder.page() - 1);
            case "next_vote" -> openVoteMenu(player, holder.page() + 1);
            case "plot_info" -> openPlotInfoMenu(player, plotId);
            case "confirm" -> openPurchaseConfirm(player, plotId);
            case "enter" -> { player.closeInventory(); plugin.enterPlot(player, plotId); }
            case "buy" -> { player.closeInventory(); plugin.purchasePlot(player, plotId); }
            case "admin_create" -> { player.closeInventory(); plugin.commands().runAdmin(player, new String[]{"create"}); }
            case "admin_wand" -> { player.closeInventory(); plugin.commands().runAdmin(player, new String[]{"wand"}); }
            case "admin_map" -> { player.closeInventory(); plugin.commands().runAdmin(player, new String[]{"mapitem"}); }
            case "admin_start" -> { player.closeInventory(); plugin.commands().runAdmin(player, new String[]{"start"}); }
            case "admin_building" -> { player.closeInventory(); plugin.commands().runAdmin(player, new String[]{"phase", "building"}); }
            case "admin_voting" -> { player.closeInventory(); plugin.commands().runAdmin(player, new String[]{"voting"}); }
            case "admin_results" -> { player.closeInventory(); plugin.showResults(player); }
            default -> {
                // Ignore unknown actions.
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CityMenuHolder) {
            event.setCancelled(true);
        }
    }

    private void fillBorder(Inventory inv, int size) {
        ItemStack pane = pane(Material.GRAY_STAINED_GLASS_PANE);
        int rows = size / 9;
        for (int slot = 0; slot < size; slot++) {
            int r = slot / 9, c = slot % 9;
            if (r == 0 || r == rows - 1 || c == 0 || c == 8) inv.setItem(slot, pane);
        }
    }

    private ItemStack pane(Material mat) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack item(Material mat, String nameMini, List<String> loreMini, String action, String plotId) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(FormatUtil.noItalic(FormatUtil.mm(nameMini)));
            if (loreMini != null && !loreMini.isEmpty()) {
                List<Component> lore = new ArrayList<>(loreMini.size());
                for (String line : loreMini) {
                    lore.add(line.isEmpty() ? Component.empty() : FormatUtil.noItalic(FormatUtil.mm(line)));
                }
                meta.lore(lore);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            if (action != null) meta.getPersistentDataContainer().set(keyAction, PersistentDataType.STRING, action);
            if (plotId != null) meta.getPersistentDataContainer().set(keyPlot, PersistentDataType.STRING, plotId);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
