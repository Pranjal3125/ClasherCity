package network.clasher.clashercity;

import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import network.clasher.clashercity.command.CityCommands;
import network.clasher.clashercity.gui.CityGui;
import network.clasher.clashercity.listener.CityProtectionListener;
import network.clasher.clashercity.map.CityMapRenderer;
import network.clasher.clashercity.model.Area;
import network.clasher.clashercity.model.Phase;
import network.clasher.clashercity.model.Plot;
import network.clasher.clashercity.model.ResultRow;
import network.clasher.clashercity.storage.CityDatabase;
import network.clasher.clashercity.util.FormatUtil;
import network.clasher.clashercity.world.CityWorldService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ClasherCity entry point — city plot sales, WorldGuard protection, physical & GUI maps,
 * building tournaments, public voting, judge rubric scoring, and queued rewards.
 */
public final class ClasherCityPlugin extends JavaPlugin {
    private Economy economy;
    private CityDatabase db;
    private CityWorldService worldService;
    private CityMapRenderer mapRenderer;
    private CityGui gui;
    private CityCommands commands;
    private NamespacedKey keyWand;

    private final Map<UUID, Location[]> selections = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyWand = new NamespacedKey(this, "selection_wand");

        Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (wg == null) {
            getLogger().severe("WorldGuard is required. ClasherCity will be disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("Vault") == null || !setupEconomy()) {
            getLogger().severe("Vault with a working economy provider is required. ClasherCity will be disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            db = new CityDatabase(new File(getDataFolder(), "clashercity.db"));
            worldService = new CityWorldService(this);
            World world = worldService.loadCityWorld();
            worldService.ensureGlobalRegion(world);
            worldService.restoreRegions(db);
            mapRenderer = new CityMapRenderer(db);
        } catch (Exception ex) {
            getLogger().severe("Startup failed: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        int rubricTotal = scoreCategories().values().stream().mapToInt(Integer::intValue).sum();
        if (rubricTotal != 100) {
            getLogger().warning("Judging category maximums total " + rubricTotal + "; recommended total is 100.");
        }

        gui = new CityGui(this, db, economy);
        commands = new CityCommands(this, db, gui);
        CityProtectionListener protection = new CityProtectionListener(this, worldService, keyWand);

        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(protection, this);
        for (String cmdName : List.of("clcity", "city", "citymap", "plots", "plot", "buildvote", "buildtop")) {
            PluginCommand cmd = getCommand(cmdName);
            if (cmd != null) {
                cmd.setExecutor(commands);
                cmd.setTabCompleter(commands);
            }
        }

        getLogger().info("ClasherCity v" + getPluginMeta().getVersion() + " enabled. Paper=" + Bukkit.getBukkitVersion()
                + ", WorldGuard=" + wg.getPluginMeta().getVersion()
                + ", Vault economy=" + economy.getName());
    }

    @Override
    public void onDisable() {
        try {
            if (db != null) db.close();
        } catch (SQLException ex) {
            getLogger().warning("Could not close SQLite database: " + ex.getMessage());
        }
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    public CityCommands commands() { return commands; }

    public Component msg(String body) {
        return FormatUtil.prefixed(getConfig().getString("messages.prefix", "&8[&6ClasherCity&8] "), body);
    }

    public void broadcast(String body) {
        Bukkit.broadcast(msg(body));
    }

    public void setSelectionCorner(UUID playerId, int index, Location location) {
        selections.computeIfAbsent(playerId, k -> new Location[2])[index] = location;
    }

    // -------------------------------------------------------------------------
    // Plot & area management
    // -------------------------------------------------------------------------

    public void ensureCityWorldReady(CommandSender sender) throws Exception {
        World world = worldService.cityWorld() != null ? worldService.cityWorld() : worldService.loadCityWorld();
        worldService.ensureGlobalRegion(world);
        sender.sendMessage(msg("<green>Flat city world ready: <yellow>" + FormatUtil.escape(world.getName()) + "</yellow>.</green>"));
    }

    public void giveSelectionWand(Player player) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        if (meta != null) {
            meta.displayName(FormatUtil.noItalic(FormatUtil.mm("<gold><bold>ClasherCity Selection Wand</bold></gold>")));
            meta.lore(List.of(
                    FormatUtil.noItalic(FormatUtil.mm("<gray>Left-click block: Position 1</gray>")),
                    FormatUtil.noItalic(FormatUtil.mm("<gray>Right-click block: Position 2</gray>"))
            ));
            meta.getPersistentDataContainer().set(keyWand, PersistentDataType.BYTE, (byte) 1);
            wand.setItemMeta(meta);
        }
        player.getInventory().addItem(wand);
        player.sendMessage(msg("<green>Selection wand given. Select two corners in the city world.</green>"));
    }

    public void saveCitySpawn(Player player) {
        if (!worldService.isCityWorld(player.getWorld())) {
            player.sendMessage(msg("<red>Stand inside the city world and run /clcity setspawn.</red>"));
            return;
        }
        Location l = player.getLocation();
        getConfig().set("city.spawn.x", l.getX());
        getConfig().set("city.spawn.y", l.getY());
        getConfig().set("city.spawn.z", l.getZ());
        getConfig().set("city.spawn.yaw", l.getYaw());
        getConfig().set("city.spawn.pitch", l.getPitch());
        getConfig().set("city.spawn-set", true);
        saveConfig();
        worldService.cityWorld().setSpawnLocation(l);
        player.sendMessage(msg("<green>City spawn saved.</green>"));
    }

    public void givePhysicalMap(Player player) {
        if (worldService.cityWorld() == null) {
            player.sendMessage(msg("<red>City world is not loaded.</red>"));
            return;
        }
        player.getInventory().addItem(mapRenderer.createMapItem(worldService.cityWorld()));
        player.sendMessage(msg("<green>Physical city map given. Place it in an item frame at spawn.</green>"));
    }

    public void showAdminInfo(CommandSender sender) throws SQLException {
        World w = worldService.cityWorld();
        sender.sendMessage(FormatUtil.mm("<gold><bold>ClasherCity</bold></gold> <dark_gray>|</dark_gray> <gray>World:</gray> <white>"
                + (w == null ? "not loaded" : FormatUtil.escape(w.getName()))
                + "</white> <dark_gray>|</dark_gray> <gray>Phase:</gray> <yellow>" + db.phase()
                + "</yellow> <dark_gray>|</dark_gray> <gray>Plots:</gray> <white>" + db.getPlots().size()
                + "</white> <dark_gray>|</dark_gray> <gray>Areas:</gray> <white>" + db.getAreas().size()
                + "</white> <dark_gray>|</dark_gray> <gray>Economy:</gray> <green>" + FormatUtil.escape(economy.getName()) + "</green>"));
    }

    public void createPlotFromSelection(Player player, String id, double price) throws Exception {
        if (!worldService.isCityWorld(player.getWorld())) throw new IllegalArgumentException("You must be in the city world.");
        if (price < 0 || !Double.isFinite(price)) throw new IllegalArgumentException("Price must be zero or greater.");
        FormatUtil.validateId(id);

        Location[] pair = selections.get(player.getUniqueId());
        if (pair == null || pair[0] == null || pair[1] == null) throw new IllegalArgumentException("Select two corners with the wand first.");
        if (!worldService.isCityWorld(pair[0].getWorld()) || !worldService.isCityWorld(pair[1].getWorld())) {
            throw new IllegalArgumentException("Both positions must be in the city world.");
        }
        int minX = Math.min(pair[0].getBlockX(), pair[1].getBlockX());
        int maxX = Math.max(pair[0].getBlockX(), pair[1].getBlockX());
        int minZ = Math.min(pair[0].getBlockZ(), pair[1].getBlockZ());
        int maxZ = Math.max(pair[0].getBlockZ(), pair[1].getBlockZ());
        int width = maxX - minX + 1, depth = maxZ - minZ + 1;
        int minSize = getConfig().getInt("plots.minimum-size", 10);
        if (width < minSize || depth < minSize) throw new IllegalArgumentException("Plot is smaller than minimum size (" + minSize + ").");
        if (db.plotExists(id)) throw new IllegalArgumentException("A plot with this ID already exists.");

        String worldName = worldService.cityWorld().getName();
        if (db.overlapsAny(worldName, minX, minZ, maxX, maxZ)) throw new IllegalArgumentException("Selection overlaps another plot, road, or public area.");

        Plot plot = new Plot(id, worldName, minX, minZ, maxX, maxZ, price, null, null);
        RegionManager manager = worldService.regionManager(worldService.cityWorld());
        if (manager == null) throw new IllegalStateException("WorldGuard region manager unavailable.");
        String regionId = worldService.plotRegionId(id);
        if (manager.hasRegion(regionId)) throw new IllegalArgumentException("WorldGuard region ID already exists.");

        db.insertPlot(plot);
        try {
            worldService.getOrCreatePlotRegion(plot);
            manager.saveChanges();
        } catch (Exception ex) {
            db.deletePlotRaw(id);
            manager.removeRegion(regionId);
            throw ex;
        }
        mapRenderer.invalidate();
        player.sendMessage(msg("<green>Plot <yellow>" + FormatUtil.escape(id) + "</yellow> created (<white>"
                + width + "x" + depth + "</white>) for <yellow>" + FormatUtil.escape(economy.format(price)) + "</yellow>.</green>"));
    }

    public void createAreaFromSelection(Player player, String id, String type) throws Exception {
        if (!worldService.isCityWorld(player.getWorld())) throw new IllegalArgumentException("You must be in the city world.");
        FormatUtil.validateId(id);
        Location[] pair = selections.get(player.getUniqueId());
        if (pair == null || pair[0] == null || pair[1] == null) throw new IllegalArgumentException("Select two corners with the wand first.");

        int minX = Math.min(pair[0].getBlockX(), pair[1].getBlockX());
        int maxX = Math.max(pair[0].getBlockX(), pair[1].getBlockX());
        int minZ = Math.min(pair[0].getBlockZ(), pair[1].getBlockZ());
        int maxZ = Math.max(pair[0].getBlockZ(), pair[1].getBlockZ());
        String worldName = worldService.cityWorld().getName();
        if (db.overlapsAny(worldName, minX, minZ, maxX, maxZ)) throw new IllegalArgumentException("Area overlaps another plot, road, or public area.");

        String regionId = worldService.areaRegionId(type, id);
        RegionManager manager = worldService.regionManager(worldService.cityWorld());
        if (manager == null || manager.hasRegion(regionId)) throw new IllegalArgumentException("WorldGuard region already exists or is unavailable.");

        Area area = new Area(id, worldName, type, minX, minZ, maxX, maxZ);
        db.insertArea(area);
        try {
            worldService.getOrCreateAreaRegion(area);
            manager.saveChanges();
        } catch (Exception ex) {
            db.deleteAreaRaw(id, type);
            manager.removeRegion(regionId);
            throw ex;
        }
        mapRenderer.invalidate();
        player.sendMessage(msg("<green>" + type + " area <yellow>" + FormatUtil.escape(id) + "</yellow> created and protected.</green>"));
    }

    public void deletePlot(String id) throws Exception {
        Plot plot = db.getPlot(id);
        if (plot == null) throw new IllegalArgumentException("Plot not found.");
        if (plot.isOwned()) throw new IllegalArgumentException("Owned plots cannot be deleted. Ask the owner to abandon it first.");
        RegionManager manager = worldService.regionManager(worldService.cityWorld());
        String regionId = worldService.plotRegionId(plot.id());
        ProtectedRegion removedRegion = manager == null ? null : manager.getRegion(regionId);
        if (manager == null || removedRegion == null) throw new IllegalStateException("Plot WorldGuard region is missing.");

        var conn = db.connection();
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement entry = conn.prepareStatement("DELETE FROM tournament_entries WHERE plot_id=?")) {
                entry.setString(1, plot.id());
                entry.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM plots WHERE id=? AND owner_uuid IS NULL")) {
                ps.setString(1, plot.id());
                if (ps.executeUpdate() != 1) throw new SQLException("Plot delete did not affect exactly one unowned plot.");
            }
            manager.removeRegion(regionId);
            manager.saveChanges();
            conn.commit();
        } catch (Exception ex) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            if (!manager.hasRegion(regionId)) {
                manager.addRegion(removedRegion);
                try { manager.saveChanges(); } catch (Exception restoreEx) {
                    getLogger().severe("Could not restore WorldGuard region " + regionId + ": " + restoreEx.getMessage());
                }
            }
            throw ex;
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
        mapRenderer.invalidate();
    }

    // -------------------------------------------------------------------------
    // Player plot actions & protection helpers
    // -------------------------------------------------------------------------

    public Plot plotAt(Location location) {
        if (!worldService.isCityWorld(location.getWorld())) return null;
        try {
            return db.plotAt(location.getWorld().getName(), location.getBlockX(), location.getBlockZ());
        } catch (SQLException ex) {
            getLogger().warning("Plot lookup failed: " + ex.getMessage());
            return null;
        }
    }

    public boolean isPlotOwner(Player player, Location location) {
        Plot plot = plotAt(location);
        return plot != null && plot.isOwned() && plot.ownerUuid().equals(player.getUniqueId().toString());
    }

    public boolean mayBuild(Player player, Location location) {
        if (player.hasPermission("clashercity.bypass")) return true;
        if (!worldService.isCityWorld(location.getWorld())) return true;
        if (db.phase() != Phase.BUILDING) return false;
        Plot plot = plotAt(location);
        if (plot == null || !plot.isOwned() || !plot.ownerUuid().equals(player.getUniqueId().toString())) return false;
        try {
            return db.isTournamentEntry(plot.id());
        } catch (SQLException ex) {
            getLogger().warning("Could not verify tournament entry for " + plot.id() + ": " + ex.getMessage());
            return false;
        }
    }

    public void purchasePlot(Player player, String id) {
        if (db.phase() != Phase.REGISTRATION) {
            player.sendMessage(msg("<red>Plot purchases are closed.</red>"));
            return;
        }
        var conn = db.connection();
        try {
            conn.setAutoCommit(false);
            Plot plot = db.getPlot(id);
            if (plot == null) { conn.rollback(); player.sendMessage(msg("<red>Plot not found.</red>")); return; }
            if (plot.isOwned()) { conn.rollback(); player.sendMessage(msg("<red>That plot is already sold.</red>")); return; }

            int maxPlots = getConfig().getBoolean("plots.allow-multiple", true) ? getConfig().getInt("plots.max-per-player", 2) : 1;
            if (db.countOwnedPlots(player.getUniqueId()) >= maxPlots) {
                conn.rollback();
                player.sendMessage(msg("<red>You have reached your plot limit of " + maxPlots + ".</red>"));
                return;
            }
            RegionManager manager = worldService.regionManager(worldService.cityWorld());
            ProtectedRegion region = manager == null ? null : manager.getRegion(worldService.plotRegionId(plot.id()));
            if (region == null) { conn.rollback(); player.sendMessage(msg("<red>Plot protection is missing. Contact an admin.</red>")); return; }
            if (!economy.has(player, plot.price())) {
                conn.rollback();
                player.sendMessage(msg("<red>Insufficient balance. Required: <yellow>" + FormatUtil.escape(economy.format(plot.price())) + "</yellow>.</red>"));
                return;
            }
            var response = economy.withdrawPlayer(player, plot.price());
            if (!response.transactionSuccess()) {
                conn.rollback();
                player.sendMessage(msg("<red>Payment failed: " + FormatUtil.escape(response.errorMessage) + "</red>"));
                return;
            }
            boolean regionChanged = false;
            try {
                region.getOwners().addPlayer(player.getUniqueId());
                regionChanged = true;
                try (PreparedStatement update = conn.prepareStatement("UPDATE plots SET owner_uuid=?,owner_name=? WHERE id=? AND owner_uuid IS NULL")) {
                    update.setString(1, player.getUniqueId().toString());
                    update.setString(2, player.getName());
                    update.setString(3, plot.id());
                    if (update.executeUpdate() != 1) throw new SQLException("Plot ownership changed during purchase.");
                }
                try (PreparedStatement tx = conn.prepareStatement("INSERT INTO transactions(plot_id,player_uuid,player_name,amount,kind) VALUES(?,?,?,?,?)")) {
                    tx.setString(1, plot.id());
                    tx.setString(2, player.getUniqueId().toString());
                    tx.setString(3, player.getName());
                    tx.setDouble(4, plot.price());
                    tx.setString(5, "PURCHASE");
                    tx.executeUpdate();
                }
                db.addTournamentEntry(plot.id(), player.getUniqueId(), player.getName());
                manager.saveChanges();
                conn.commit();
                mapRenderer.invalidate();
                player.sendMessage(msg("<green>You purchased plot <yellow>" + FormatUtil.escape(plot.id())
                        + "</yellow> for <yellow>" + FormatUtil.escape(economy.format(plot.price())) + "</yellow>!</green>"));
                player.sendMessage(msg("<gray>Use <yellow>/plot home " + FormatUtil.escape(plot.id()) + "</yellow> to visit your plot.</gray>"));
            } catch (Exception ex) {
                conn.rollback();
                if (regionChanged) {
                    region.getOwners().removePlayer(player.getUniqueId());
                    try { manager.saveChanges(); } catch (Exception ignored) {}
                }
                var refund = economy.depositPlayer(player, plot.price());
                if (refund.transactionSuccess()) {
                    player.sendMessage(msg("<yellow>Purchase failed. Your payment was refunded.</yellow>"));
                } else {
                    player.sendMessage(msg("<red>Purchase failed and automatic refund also failed. Contact an admin immediately.</red>"));
                    getLogger().severe("CRITICAL: refund failed for " + player.getName() + " after plot " + plot.id() + " rollback: " + refund.errorMessage);
                }
                getLogger().warning("Plot purchase rolled back: " + ex.getMessage());
            }
        } catch (Exception ex) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            player.sendMessage(msg("<red>Database error while purchasing plot.</red>"));
            getLogger().warning("Purchase error: " + ex.getMessage());
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    public void abandonPlot(Player player, String id) {
        Phase current = db.phase();
        if (current != Phase.SETUP && current != Phase.REGISTRATION) {
            player.sendMessage(msg("<red>Plots can only be released during SETUP or REGISTRATION.</red>"));
            return;
        }
        try {
            Plot plot = db.getPlot(id);
            if (plot == null || !plot.isOwned() || !plot.ownerUuid().equals(player.getUniqueId().toString())) {
                player.sendMessage(msg("<red>You do not own that plot.</red>"));
                return;
            }
            RegionManager manager = worldService.regionManager(worldService.cityWorld());
            ProtectedRegion region = manager == null ? null : manager.getRegion(worldService.plotRegionId(plot.id()));
            if (region == null) { player.sendMessage(msg("<red>Plot region is missing. Contact an admin.</red>")); return; }

            boolean ownerRemoved = region.getOwners().contains(player.getUniqueId());
            if (ownerRemoved) region.getOwners().removePlayer(player.getUniqueId());
            var conn = db.connection();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement("UPDATE plots SET owner_uuid=NULL,owner_name=NULL WHERE id=? AND owner_uuid=?")) {
                    ps.setString(1, plot.id());
                    ps.setString(2, player.getUniqueId().toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("Ownership update failed");
                }
                if (current == Phase.REGISTRATION) {
                    try (PreparedStatement entry = conn.prepareStatement("DELETE FROM tournament_entries WHERE tournament_id=? AND plot_id=?")) {
                        entry.setString(1, db.tournamentId());
                        entry.setString(2, plot.id());
                        entry.executeUpdate();
                    }
                }
                manager.saveChanges();
                conn.commit();
            } catch (Exception ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                if (ownerRemoved) region.getOwners().addPlayer(player.getUniqueId());
                try { manager.saveChanges(); } catch (Exception saveEx) {
                    getLogger().severe("Could not restore WorldGuard owner for " + plot.id() + ": " + saveEx.getMessage());
                }
                throw ex;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
            if (getConfig().getBoolean("plots.refund-on-abandon", false)) {
                var refund = economy.depositPlayer(player, plot.price());
                if (!refund.transactionSuccess()) {
                    getLogger().severe("Refund failed for " + player.getName() + " plot " + plot.id() + ": " + refund.errorMessage);
                }
            }
            mapRenderer.invalidate();
            player.sendMessage(msg("<green>You released plot <yellow>" + FormatUtil.escape(plot.id()) + "</yellow>.</green>"));
        } catch (Exception ex) {
            player.sendMessage(msg("<red>Could not release plot: " + FormatUtil.escape(ex.getMessage()) + "</red>"));
        }
    }

    public void enterPlot(Player player, String id) {
        if (db.phase() != Phase.REGISTRATION) {
            player.sendMessage(msg("<red>Plot entries are open only during registration.</red>"));
            return;
        }
        try {
            Plot plot = db.getPlot(id);
            if (plot == null || !plot.isOwned() || !plot.ownerUuid().equals(player.getUniqueId().toString())) {
                player.sendMessage(msg("<red>You must own this plot to enter it in the tournament.</red>"));
                return;
            }
            if (db.isTournamentEntry(plot.id())) {
                player.sendMessage(msg("<yellow>This plot is already entered in the current tournament.</yellow>"));
                return;
            }
            int maxEntries = getConfig().getBoolean("plots.allow-multiple", true) ? getConfig().getInt("plots.max-per-player", 2) : 1;
            if (db.countTournamentEntries(player.getUniqueId()) >= maxEntries) {
                player.sendMessage(msg("<red>You have reached the tournament entry limit of " + maxEntries + ".</red>"));
                return;
            }
            db.addTournamentEntry(plot.id(), player.getUniqueId(), player.getName());
            player.sendMessage(msg("<green>Plot <yellow>" + FormatUtil.escape(plot.id()) + "</yellow> entered in <yellow>" + FormatUtil.escape(db.tournamentName()) + "</yellow>!</green>"));
        } catch (SQLException ex) {
            player.sendMessage(msg("<red>Could not enter plot: " + FormatUtil.escape(ex.getMessage()) + "</red>"));
        }
    }

    public void showPlotInfo(Player player, String id) {
        try {
            Plot p = db.getPlot(id);
            if (p == null) { player.sendMessage(msg("<red>Plot not found.</red>")); return; }
            String owner = p.isOwned() ? p.ownerName() : "Available";
            player.sendMessage(FormatUtil.mm("<gold>Plot <yellow>" + FormatUtil.escape(p.id()) + "</yellow></gold>"
                    + " <dark_gray>|</dark_gray> <gray>Size:</gray> <white>" + p.width() + "x" + p.depth() + "</white>"
                    + " <dark_gray>|</dark_gray> <gray>Price:</gray> <yellow>" + FormatUtil.escape(economy.format(p.price())) + "</yellow>"
                    + " <dark_gray>|</dark_gray> <gray>Owner:</gray> <white>" + FormatUtil.escape(owner) + "</white>"));
        } catch (SQLException ex) {
            player.sendMessage(msg("<red>Database error.</red>"));
        }
    }

    public void teleportCitySpawn(Player player) {
        if (worldService.cityWorld() == null) {
            player.sendMessage(msg("<red>City world is not loaded.</red>"));
            return;
        }
        player.teleport(worldService.cityWorld().getSpawnLocation());
        player.sendMessage(msg("<green>Teleported to ClasherCity spawn.</green>"));
    }

    public void teleportHome(Player player, String id) {
        try {
            Plot plot;
            if (id != null) {
                plot = db.getPlot(id);
                if (plot == null || !plot.isOwned() || !plot.ownerUuid().equals(player.getUniqueId().toString())) {
                    player.sendMessage(msg("<red>You do not own that plot.</red>"));
                    return;
                }
            } else {
                plot = db.getFirstOwnedPlot(player.getUniqueId());
            }
            if (plot == null) { player.sendMessage(msg("<red>You do not own a city plot.</red>")); return; }
            if (!worldService.teleportToPlot(player, plot)) player.sendMessage(msg("<red>City world is not loaded.</red>"));
        } catch (SQLException ex) {
            player.sendMessage(msg("<red>Database error.</red>"));
        }
    }

    public void teleportToPlotById(Player player, String id) {
        try {
            Plot plot = db.getPlot(id);
            if (plot == null) { player.sendMessage(msg("<red>Plot not found.</red>")); return; }
            if (worldService.teleportToPlot(player, plot)) {
                player.sendMessage(msg("<green>Teleported to plot <yellow>" + FormatUtil.escape(plot.id()) + "</yellow>.</green>"));
            } else {
                player.sendMessage(msg("<red>City world is not loaded.</red>"));
            }
        } catch (SQLException ex) {
            player.sendMessage(msg("<red>Database error.</red>"));
        }
    }

    // -------------------------------------------------------------------------
    // Voting, judging, leaderboard & rewards
    // -------------------------------------------------------------------------

    public boolean isEligibleVoter(Player player) {
        int minimumMinutes = getConfig().getInt("voting.minimum-playtime-minutes", 0);
        if (minimumMinutes <= 0) return true;
        try {
            return player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200 >= minimumMinutes;
        } catch (Exception ex) {
            return true;
        }
    }

    public void castVote(Player player, String plotId) {
        if (db.phase() != Phase.VOTING) { player.sendMessage(msg("<red>Voting is closed.</red>")); return; }
        if (!isEligibleVoter(player)) { player.sendMessage(msg("<red>You do not meet the minimum playtime requirement.</red>")); return; }
        try {
            Plot plot = db.getPlot(plotId);
            if (plot == null || !plot.isOwned() || !db.isTournamentEntry(plot.id())) {
                player.sendMessage(msg("<red>This entry is unavailable.</red>"));
                return;
            }
            if (plot.ownerUuid().equals(player.getUniqueId().toString())) {
                player.sendMessage(msg("<red>You cannot vote for your own plot.</red>"));
                return;
            }
            try {
                db.recordVote(player.getUniqueId(), player.getName(), plot.id());
                player.closeInventory();
                player.sendMessage(msg("<green>Your vote for <yellow>" + FormatUtil.escape(plot.id()) + "</yellow> has been recorded!</green>"));
            } catch (SQLException ex) {
                String detail = Objects.toString(ex.getMessage(), "").toLowerCase(Locale.ROOT);
                if (ex.getErrorCode() == 19 || detail.contains("unique constraint")) {
                    player.sendMessage(msg("<yellow>You have already voted in this tournament.</yellow>"));
                } else {
                    getLogger().warning("Vote insert failed: " + ex.getMessage());
                    player.sendMessage(msg("<red>Could not save vote. Please contact staff.</red>"));
                }
            }
        } catch (SQLException ex) {
            player.sendMessage(msg("<red>Could not save vote.</red>"));
        }
    }

    public Map<String, Integer> scoreCategories() {
        Map<String, Integer> map = new LinkedHashMap<>();
        var section = getConfig().getConfigurationSection("judging.categories");
        if (section != null) {
            for (String key : section.getKeys(false)) map.put(key.toLowerCase(Locale.ROOT), section.getInt(key));
        }
        if (map.isEmpty()) {
            map.put("creativity", 25);
            map.put("exterior", 25);
            map.put("interior", 20);
            map.put("detailing", 15);
            map.put("theme", 15);
        }
        return map;
    }

    public void startTournament(CommandSender sender, String name) throws SQLException {
        Phase current = db.phase();
        if (current == Phase.REGISTRATION || current == Phase.BUILDING || current == Phase.VOTING) {
            sender.sendMessage(msg("<red>A tournament is already active in phase " + current + ". Finish it first.</red>"));
            return;
        }
        db.startNewTournament(name);
        sender.sendMessage(msg("<green>Tournament started: <yellow>" + FormatUtil.escape(name) + "</yellow>. Registration is now open.</green>"));
        broadcast("<gold>" + FormatUtil.escape(name) + "</gold> <green>has started! Use <yellow>/city</yellow> or <yellow>/plots</yellow> to claim a plot.</green>");
    }

    public void closeTournament(CommandSender sender) throws SQLException {
        if (db.phase() == Phase.RESULTS) {
            sender.sendMessage(msg("<yellow>Results have already been finalized.</yellow>"));
            showResults(sender);
            return;
        }
        db.setPhase(Phase.RESULTS);
        List<ResultRow> results = calculateResults();
        broadcast("<green>Voting has closed! Final results are in:</green>");
        if (results.isEmpty()) {
            sender.sendMessage(msg("<yellow>No entered plots were available for final scoring.</yellow>"));
            return;
        }
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            ResultRow row = results.get(i);
            Bukkit.broadcast(FormatUtil.mm("<gold>#" + (i + 1) + "</gold> <yellow>" + FormatUtil.escape(row.ownerName())
                    + "</yellow> <gray>(plot " + FormatUtil.escape(row.plotId()) + ")</gray> <dark_gray>-</dark_gray> <white>"
                    + String.format(Locale.US, "%.2f", row.finalScore()) + " pts</white>"));
        }
        queueAndDeliverRewards(results);
        sender.sendMessage(msg("<green>Results finalized. Use <yellow>/buildtop</yellow> to view the leaderboard.</green>"));
    }

    public void judgeCommand(CommandSender sender, String[] args) throws SQLException {
        if (args.length < 3) {
            sender.sendMessage(msg("<yellow>Usage: /clcity judge <add|remove> <player></yellow>"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        String name = target.getName() == null ? args[2] : target.getName();
        if (action.equals("add")) {
            db.addJudge(target.getUniqueId(), name);
            sender.sendMessage(msg("<green>Judge added: <yellow>" + FormatUtil.escape(name) + "</yellow>.</green>"));
        } else if (action.equals("remove")) {
            db.removeJudge(target.getUniqueId());
            sender.sendMessage(msg("<yellow>Judge removed: " + FormatUtil.escape(name) + ".</yellow>"));
        } else {
            sender.sendMessage(msg("<yellow>Usage: /clcity judge <add|remove> <player></yellow>"));
        }
    }

    public void scoreCommand(CommandSender sender, String[] args) throws SQLException {
        if (args.length < 4) {
            sender.sendMessage(msg("<yellow>Usage: /clcity score <plot> <category> <marks></yellow>"));
            return;
        }
        if (db.phase() != Phase.VOTING) {
            sender.sendMessage(msg("<red>Judges can submit or revise scores only during the VOTING phase.</red>"));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only an online judge or admin player can submit scores.");
            return;
        }
        if (!player.hasPermission("clashercity.admin") && !db.isJudge(player.getUniqueId())) {
            player.sendMessage(msg("<red>You are not registered as a judge for this tournament.</red>"));
            return;
        }
        Plot plot = db.getPlot(args[1]);
        if (plot == null || !plot.isOwned() || !db.isTournamentEntry(plot.id())) {
            sender.sendMessage(msg("<red>Owned tournament entry not found.</red>"));
            return;
        }
        String category = args[2].toLowerCase(Locale.ROOT);
        Map<String, Integer> categories = scoreCategories();
        Integer max = categories.get(category);
        if (max == null) {
            sender.sendMessage(msg("<red>Valid categories: " + String.join(", ", categories.keySet()) + "</red>"));
            return;
        }
        int score = Integer.parseInt(args[3]);
        if (score < 0 || score > max) {
            sender.sendMessage(msg("<red>Marks for " + category + " must be between 0 and " + max + ".</red>"));
            return;
        }
        db.saveScore(player.getUniqueId(), plot.id(), category, score);
        sender.sendMessage(msg("<green>Saved <yellow>" + score + "/" + max + "</yellow> for " + category + " on <yellow>" + FormatUtil.escape(plot.id()) + "</yellow>.</green>"));
    }

    public void winnerOverrideCommand(CommandSender sender, String[] args) throws SQLException {
        if (args.length < 3) {
            sender.sendMessage(msg("<yellow>Usage: /clcity winner <1|2|3> <plot-id|auto></yellow>"));
            return;
        }
        int rank = Integer.parseInt(args[1]);
        if (rank < 1 || rank > 3) throw new IllegalArgumentException("Rank must be 1, 2, or 3.");
        String plotId = args[2];
        if (!plotId.equalsIgnoreCase("auto")) {
            Plot plot = db.getPlot(plotId);
            if (plot == null || !plot.isOwned() || !db.isTournamentEntry(plot.id())) {
                throw new IllegalArgumentException("Choose an owned plot entered in the current tournament.");
            }
            for (int other = 1; other <= 3; other++) {
                if (other == rank) continue;
                String existing = db.getMeta("winner_" + other);
                if (existing != null && existing.equalsIgnoreCase(plot.id())) {
                    throw new IllegalArgumentException("Plot already manually assigned to rank " + other + ". Clear that override first.");
                }
            }
            db.setMeta("winner_" + rank, plot.id());
            sender.sendMessage(msg("<green>Manual winner override set: rank " + rank + " = <yellow>" + FormatUtil.escape(plot.id()) + "</yellow>.</green>"));
        } else {
            db.setMeta("winner_" + rank, "");
            sender.sendMessage(msg("<yellow>Rank " + rank + " returned to automatic scoring.</yellow>"));
        }
    }

    public List<ResultRow> calculateResults() throws SQLException {
        return db.calculateResults(
                scoreCategories().size(),
                getConfig().getDouble("voting.player-weight", 50.0),
                getConfig().getDouble("voting.judge-weight", 50.0)
        );
    }

    public void showResults(CommandSender sender) {
        try {
            List<ResultRow> results = calculateResults();
            sender.sendMessage(FormatUtil.mm("<gold><bold>---- ClasherCity Leaderboard ----</bold></gold>"));
            sender.sendMessage(FormatUtil.mm("<gray>Tournament:</gray> <white>" + FormatUtil.escape(db.tournamentName())
                    + "</white> <dark_gray>|</dark_gray> <gray>Phase:</gray> <yellow>" + db.phase() + "</yellow>"));
            if (results.isEmpty()) {
                sender.sendMessage(FormatUtil.mm("<red>No tournament plot entries yet.</red>"));
                return;
            }
            for (int i = 0; i < results.size(); i++) {
                ResultRow r = results.get(i);
                sender.sendMessage(FormatUtil.mm("<yellow>#" + (i + 1) + "</yellow> <white>" + FormatUtil.escape(r.ownerName())
                        + "</white> <gray>(" + FormatUtil.escape(r.plotId()) + ")</gray> <dark_gray>-</dark_gray> <green>"
                        + String.format(Locale.US, "%.2f", r.finalScore()) + "</green> <gray>| votes " + r.votes()
                        + " | judge " + String.format(Locale.US, "%.1f", r.judgeScore()) + "</gray>"));
            }
        } catch (SQLException ex) {
            sender.sendMessage(msg("<red>Could not calculate results.</red>"));
        }
    }

    public void queueAndDeliverRewards(List<ResultRow> results) throws SQLException {
        String[] paths = {"rewards.first-commands", "rewards.second-commands", "rewards.third-commands"};
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            ResultRow row = results.get(i);
            List<String> cmds = getConfig().getStringList(paths[i]);
            if (cmds.isEmpty()) {
                getLogger().warning("No reward commands configured for rank " + (i + 1) + ". Set " + paths[i] + " in config.yml.");
                continue;
            }
            UUID ownerId = UUID.fromString(row.ownerUuid());
            db.queueReward(ownerId, row.ownerName(), i + 1, row.plotId());
            Player online = Bukkit.getPlayer(ownerId);
            if (online != null) deliverPendingRewards(online);
            else broadcast("<gray>Prize for <yellow>" + FormatUtil.escape(row.ownerName()) + "</yellow> is queued until they next join.</gray>");
        }
    }

    public void deliverPendingRewards(Player player) {
        try {
            for (CityDatabase.PendingReward pending : db.pendingRewards(player.getUniqueId())) {
                String path = pending.rank() == 1 ? "rewards.first-commands"
                        : pending.rank() == 2 ? "rewards.second-commands"
                        : "rewards.third-commands";
                List<String> cmds = getConfig().getStringList(path);
                if (cmds.isEmpty()) {
                    db.markRewardStatus(pending.tournamentId(), player.getUniqueId(), pending.rank(), "REVIEW");
                    getLogger().warning("Reward commands empty for rank " + pending.rank() + "; marked REVIEW for " + player.getName());
                    continue;
                }
                boolean dispatched = true;
                for (String raw : cmds) {
                    String cmd = raw.replace("{player}", player.getName())
                            .replace("{uuid}", player.getUniqueId().toString())
                            .replace("{plot}", pending.plotId())
                            .replace("{rank}", Integer.toString(pending.rank()))
                            .trim();
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    try {
                        if (cmd.isBlank() || !Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)) dispatched = false;
                    } catch (RuntimeException ex) {
                        dispatched = false;
                        getLogger().warning("Reward command threw error for " + player.getName() + ": " + ex.getMessage());
                    }
                }
                if (dispatched) {
                    db.markRewardStatus(pending.tournamentId(), player.getUniqueId(), pending.rank(), "DELIVERED");
                    player.sendMessage(msg("<green>Your rank #" + pending.rank() + " tournament reward has been delivered!</green>"));
                } else {
                    db.markRewardStatus(pending.tournamentId(), player.getUniqueId(), pending.rank(), "REVIEW");
                    getLogger().warning("Reward command for " + player.getName() + " rank " + pending.rank() + " returned false; marked REVIEW.");
                    player.sendMessage(msg("<yellow>Your reward needs admin review. Please contact staff.</yellow>"));
                }
            }
        } catch (Exception ex) {
            getLogger().warning("Reward delivery failed: " + ex.getMessage());
        }
    }

    public void rewardCommand(CommandSender sender, String[] args) throws SQLException {
        if (args.length >= 2 && args[1].equalsIgnoreCase("distribute")) {
            queueAndDeliverRewards(calculateResults());
            sender.sendMessage(msg("<green>Configured rewards queued/dispatched for the current leaderboard.</green>"));
        } else if (args.length >= 3 && args[1].equalsIgnoreCase("retry")) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            int count = db.resetRewardsForRetry(target.getUniqueId());
            sender.sendMessage(msg("<yellow>Pending/review reward records reset: " + count + ". Verify no reward was already granted before retrying.</yellow>"));
            Player online = Bukkit.getPlayer(target.getUniqueId());
            if (online != null && count > 0) deliverPendingRewards(online);
        } else {
            sender.sendMessage(msg("<yellow>Usage: /clcity reward <distribute|retry <player>></yellow>"));
        }
    }
}
