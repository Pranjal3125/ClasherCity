package network.clasher.clashercity;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.List;

/**
 * ClasherCity - city plot sales and building tournament management for Clasher Network.
 * WorldGuard is the protection source of truth; GriefPrevention is not modified.
 */
public final class ClasherCityPlugin extends JavaPlugin implements Listener {
    private Economy economy;
    private Connection db;
    private World cityWorld;
    private NamespacedKey keyAction;
    private NamespacedKey keyPlot;
    private NamespacedKey keyWand;
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, Long> lastGuiAction = new HashMap<>();
    private final MapViewRenderer mapRenderer = new MapViewRenderer();

    private enum Phase { SETUP, REGISTRATION, BUILDING, VOTING, RESULTS }
    private record Plot(String id, String world, int minX, int minZ, int maxX, int maxZ,
                        double price, String ownerUuid, String ownerName) {}
    private record Area(String id, String world, String type, int minX, int minZ, int maxX, int maxZ) {}
    private record ResultRow(String plotId, String ownerUuid, String ownerName, int votes,
                             double playerScore, double judgeScore, double finalScore) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyAction = new NamespacedKey(this, "action");
        keyPlot = new NamespacedKey(this, "plot_id");
        keyWand = new NamespacedKey(this, "selection_wand");

        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
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
            openDatabase();
            ensureTournamentMeta();
            loadCityWorld();
            if (cityWorld != null) {
                ensureGlobalRegion(cityWorld);
                restoreRegions();
            }
        } catch (Exception ex) {
            getLogger().severe("Startup failed: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        int rubricTotal = scoreCategories().values().stream().mapToInt(Integer::intValue).sum();
        if (rubricTotal != 100) getLogger().warning("Judging category maximums total " + rubricTotal + "; the recommended total is exactly 100. Review judging.categories in config.yml.");
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("ClasherCity enabled. Paper=" + Bukkit.getBukkitVersion()
                + ", WorldGuard=" + Bukkit.getPluginManager().getPlugin("WorldGuard").getDescription().getVersion()
                + ", Vault economy=" + economy.getName());
        if (Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") == null) {
            getLogger().warning("FAWE was not detected. WorldGuard still works; FAWE is not required by ClasherCity.");
        }
    }

    @Override
    public void onDisable() {
        try {
            if (db != null && !db.isClosed()) db.close();
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

    private void openDatabase() throws SQLException {
        File folder = getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) throw new SQLException("Cannot create plugin data folder");
        db = DriverManager.getConnection("jdbc:sqlite:" + new File(folder, "clashercity.db").getAbsolutePath());
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS plots (id TEXT PRIMARY KEY, world TEXT NOT NULL, min_x INTEGER NOT NULL, min_z INTEGER NOT NULL, max_x INTEGER NOT NULL, max_z INTEGER NOT NULL, price REAL NOT NULL, owner_uuid TEXT, owner_name TEXT, created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS tournament_entries (tournament_id TEXT NOT NULL, plot_id TEXT NOT NULL, owner_uuid TEXT NOT NULL, owner_name TEXT NOT NULL, entered_at TEXT DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(tournament_id, plot_id))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS areas (id TEXT PRIMARY KEY, world TEXT NOT NULL, type TEXT NOT NULL, min_x INTEGER NOT NULL, min_z INTEGER NOT NULL, max_x INTEGER NOT NULL, max_z INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, plot_id TEXT NOT NULL, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, amount REAL NOT NULL, kind TEXT NOT NULL, created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS votes (tournament_id TEXT NOT NULL, voter_uuid TEXT NOT NULL, voter_name TEXT NOT NULL, plot_id TEXT NOT NULL, created_at TEXT DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(tournament_id, voter_uuid))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS judges (tournament_id TEXT NOT NULL, judge_uuid TEXT NOT NULL, judge_name TEXT NOT NULL, PRIMARY KEY(tournament_id, judge_uuid))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS scores (tournament_id TEXT NOT NULL, judge_uuid TEXT NOT NULL, plot_id TEXT NOT NULL, category TEXT NOT NULL, score INTEGER NOT NULL, updated_at TEXT DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(tournament_id, judge_uuid, plot_id, category))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS reward_queue (tournament_id TEXT NOT NULL, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, rank INTEGER NOT NULL, plot_id TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING', created_at TEXT DEFAULT CURRENT_TIMESTAMP, delivered_at TEXT, PRIMARY KEY(tournament_id, player_uuid, rank))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS plot_visit_settings (player_uuid TEXT PRIMARY KEY, visits_enabled INTEGER NOT NULL DEFAULT 1)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS construction_progress (plot_id TEXT PRIMARY KEY, milestone TEXT NOT NULL DEFAULT 'FOUNDATION', percent INTEGER NOT NULL DEFAULT 0, deadline TEXT, updated_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS plot_gallery (id INTEGER PRIMARY KEY AUTOINCREMENT, plot_id TEXT NOT NULL, owner_uuid TEXT NOT NULL, owner_name TEXT NOT NULL, image_url TEXT NOT NULL, caption TEXT NOT NULL DEFAULT '', created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS worldgen_operations (id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE', created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS worldgen_changes (operation_id TEXT NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, old_data TEXT NOT NULL, new_data TEXT NOT NULL, restored INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(operation_id,world,x,y,z))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS plot_boards (plot_id TEXT PRIMARY KEY, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL)");
        }
    }

    private void ensureTournamentMeta() throws SQLException {
        if (getMeta("phase") == null) setMeta("phase", "SETUP");
        if (getMeta("tournament_id") == null) setMeta("tournament_id", UUID.randomUUID().toString());
        if (getMeta("tournament_name") == null) setMeta("tournament_name", "ClasherCity Building Championship");
    }

    private String getMeta(String key) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private String tournamentId() {
        try { return getMeta("tournament_id"); }
        catch (SQLException ex) { throw new IllegalStateException(ex); }
    }

    private String tournamentName() {
        try { return Objects.requireNonNullElse(getMeta("tournament_name"), "ClasherCity Championship"); }
        catch (SQLException ex) { return "ClasherCity Championship"; }
    }

    private Phase phase() {
        try { return Phase.valueOf(Objects.requireNonNullElse(getMeta("phase"), "SETUP").toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { return Phase.SETUP; }
    }

    private void setPhase(Phase phase) throws SQLException {
        setMeta("phase", phase.name());
    }

    private void loadCityWorld() {
        String worldName = getConfig().getString("city.world", "clasher_city");
        cityWorld = Bukkit.getWorld(worldName);
        if (cityWorld == null && getConfig().getBoolean("city.auto-load-world", true)) {
            WorldCreator creator = new WorldCreator(worldName)
                    .type(WorldType.FLAT)
                    .environment(World.Environment.NORMAL)
                    .generateStructures(false);
            cityWorld = Bukkit.createWorld(creator);
        }
        if (cityWorld == null) throw new IllegalStateException("Could not load/create city world " + worldName);
        cityWorld.setAutoSave(true);
        if (getConfig().getBoolean("city.spawn-set", false)) {
            Location configuredSpawn = new Location(cityWorld,
                    getConfig().getDouble("city.spawn.x", 0.5),
                    getConfig().getDouble("city.spawn.y", cityWorld.getHighestBlockYAt(0, 0) + 1),
                    getConfig().getDouble("city.spawn.z", 0.5),
                    (float) getConfig().getDouble("city.spawn.yaw", 0.0),
                    (float) getConfig().getDouble("city.spawn.pitch", 0.0));
            cityWorld.setSpawnLocation(configuredSpawn);
        } else {
            int y = cityWorld.getHighestBlockYAt(0, 0) + 1;
            cityWorld.setSpawnLocation(0, y, 0);
        }
    }

    private RegionManager regionManager(World world) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
    }

    private void ensureGlobalRegion(World world) throws Exception {
        RegionManager manager = regionManager(world);
        if (manager == null) throw new IllegalStateException("WorldGuard region manager unavailable for " + world.getName());
        ProtectedRegion global = manager.getRegion("__global__");
        if (global == null) {
            global = new GlobalProtectedRegion("__global__");
            manager.addRegion(global);
        }
        global.setFlag(Flags.BUILD, StateFlag.State.DENY);
        global.setFlag(Flags.INTERACT, StateFlag.State.DENY);
        global.setFlag(Flags.CHEST_ACCESS, StateFlag.State.DENY);
        global.setFlag(Flags.PVP, StateFlag.State.DENY);
        global.setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
        global.setFlag(Flags.TNT, StateFlag.State.DENY);
        global.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY);
        manager.saveChanges();
    }

    private void restoreRegions() throws Exception {
        for (Plot plot : getPlots()) {
            if (!plot.world().equals(cityWorld.getName())) continue;
            ProtectedRegion region = getOrCreatePlotRegion(plot);
            region.getOwners().clear();
            if (plot.ownerUuid() != null) region.getOwners().addPlayer(UUID.fromString(plot.ownerUuid()));
        }
        for (Area area : getAreas()) {
            if (!area.world().equals(cityWorld.getName())) continue;
            getOrCreateAreaRegion(area);
        }
        RegionManager manager = regionManager(cityWorld);
        if (manager != null) manager.saveChanges();
    }

    private ProtectedRegion getOrCreatePlotRegion(Plot plot) {
        RegionManager manager = regionManager(cityWorld);
        if (manager == null) throw new IllegalStateException("WorldGuard RegionManager unavailable");
        String regionId = plotRegionId(plot.id());
        ProtectedRegion region = manager.getRegion(regionId);
        if (region == null) {
            region = new ProtectedCuboidRegion(regionId,
                    BlockVector3.at(plot.minX(), cityWorld.getMinHeight(), plot.minZ()),
                    BlockVector3.at(plot.maxX(), cityWorld.getMaxHeight() - 1, plot.maxZ()));
            manager.addRegion(region);
        }
        region.setPriority(10);
        // Region membership drives WorldGuard's implicit build permission.
        // The explicit flag is restricted to members, not everybody in the plot.
        region.setFlag(Flags.BUILD, StateFlag.State.ALLOW);
        region.setFlag(Flags.BUILD.getRegionGroupFlag(), RegionGroup.MEMBERS);
        region.setFlag(Flags.INTERACT, StateFlag.State.ALLOW);
        region.setFlag(Flags.INTERACT.getRegionGroupFlag(), RegionGroup.MEMBERS);
        region.setFlag(Flags.CHEST_ACCESS, StateFlag.State.ALLOW);
        region.setFlag(Flags.CHEST_ACCESS.getRegionGroupFlag(), RegionGroup.MEMBERS);
        region.setFlag(Flags.PVP, StateFlag.State.DENY);
        region.setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
        region.setFlag(Flags.TNT, StateFlag.State.DENY);
        region.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY);
        return region;
    }

    private ProtectedRegion getOrCreateAreaRegion(Area area) {
        RegionManager manager = regionManager(cityWorld);
        if (manager == null) throw new IllegalStateException("WorldGuard RegionManager unavailable");
        String id = areaRegionId(area.type(), area.id());
        ProtectedRegion region = manager.getRegion(id);
        if (region == null) {
            region = new ProtectedCuboidRegion(id,
                    BlockVector3.at(area.minX(), cityWorld.getMinHeight(), area.minZ()),
                    BlockVector3.at(area.maxX(), cityWorld.getMaxHeight() - 1, area.maxZ()));
            manager.addRegion(region);
        }
        region.setPriority(20);
        region.setFlag(Flags.BUILD, StateFlag.State.DENY);
        region.setFlag(Flags.INTERACT, StateFlag.State.DENY);
        region.setFlag(Flags.CHEST_ACCESS, StateFlag.State.DENY);
        region.setFlag(Flags.PVP, StateFlag.State.DENY);
        region.setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
        region.setFlag(Flags.TNT, StateFlag.State.DENY);
        region.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY);
        return region;
    }

    private String plotRegionId(String id) {
        return getConfig().getString("protection.plot-region-prefix", "plot_") + id.toLowerCase(Locale.ROOT);
    }

    private String areaRegionId(String type, String id) {
        String prefix = type.equalsIgnoreCase("ROAD")
                ? getConfig().getString("protection.road-region-prefix", "road_")
                : getConfig().getString("protection.public-region-prefix", "public_");
        return prefix + id.toLowerCase(Locale.ROOT);
    }

    private String msg(String text) {
        return color(getConfig().getString("messages.prefix", "&8[&6ClasherCity&8] ") + text);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private boolean isCityWorld(World world) {
        return cityWorld != null && world != null && world.getUID().equals(cityWorld.getUID());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("clcity")) return adminCommand(sender, args);
        if (name.equals("city")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
            if (args.length > 0 && args[0].equalsIgnoreCase("visit")) {
                teleportCitySpawn(player);
            } else {
                openMainMenu(player);
            }
            return true;
        }
        if (name.equals("citymap")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
            openMapMenu(player, 0);
            return true;
        }
        if (name.equals("plots")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
            openPlotMenu(player, 0);
            return true;
        }
        if (name.equals("buildvote")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
            openVoteMenu(player);
            return true;
        }
        if (name.equals("buildtop")) {
            showResults(sender);
            return true;
        }
        if (name.equals("plot")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
            return playerPlotCommand(player, args);
        }
        return false;
    }

    private boolean playerPlotCommand(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(msg("Usage: /plot <claim|enter|home|visit|visits|info|abandon> [plot-id|player|on|off]"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "claim" -> {
                if (args.length < 2) player.sendMessage(msg("Usage: /plot claim <plot-id>"));
                else openPurchaseConfirm(player, args[1]);
            }
            case "enter" -> {
                if (args.length < 2) player.sendMessage(msg("Usage: /plot enter <plot-id>"));
                else enterPlot(player, args[1]);
            }
            case "home" -> teleportHome(player, args.length >= 2 ? args[1] : null);
            case "visit" -> {
                if (args.length < 2) player.sendMessage(msg("Usage: /plot visit <player>"));
                else visitPlayerPlot(player, args[1]);
            }
            case "visits" -> {
                if (args.length < 2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) player.sendMessage(msg("Usage: /plot visits <on|off>"));
                else setPlotVisits(player, args[1].equalsIgnoreCase("on"));
            }
            case "progress" -> {
                if (args.length < 4) player.sendMessage(msg("Usage: /plot progress <plot-id> <foundation|structure|exterior|interior|detailing|completed> <0-100> [YYYY-MM-DD]"));
                else updateConstructionProgress(player, args);
            }
            case "gallery" -> {
                if (args.length < 2) player.sendMessage(msg("Usage: /plot gallery <plot-id> [image-url] [caption]"));
                else if (args.length == 2) showPlotGallery(player, args[1]);
                else submitPlotScreenshot(player, args);
            }
            case "info" -> {
                if (args.length < 2) player.sendMessage(msg("Usage: /plot info <plot-id>"));
                else showPlotInfo(player, args[1]);
            }
            case "abandon" -> {
                if (args.length < 2) player.sendMessage(msg("Usage: /plot abandon <plot-id>"));
                else abandonPlot(player, args[1]);
            }
            default -> player.sendMessage(msg("Usage: /plot <claim|enter|home|visit|visits|progress|gallery|info|abandon> [arguments]"));
        }
        return true;
    }

    private boolean adminCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendAdminHelp(sender);
            return true;
        }
        if (!sender.hasPermission("clashercity.admin")) {
            sender.sendMessage(msg("You do not have permission."));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "admin" -> {
                    if (sender instanceof Player p) openAdminMenu(p);
                    else sendAdminHelp(sender);
                }
                case "create" -> {
                    if (cityWorld == null) loadCityWorld();
                    ensureGlobalRegion(cityWorld);
                    sender.sendMessage(msg("Flat city world ready: " + cityWorld.getName()));
                }
                case "tp", "visit" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); break; }
                    teleportCitySpawn(p);
                }
                case "wand" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); break; }
                    ItemStack wand = new ItemStack(Material.BLAZE_ROD);
                    ItemMeta meta = wand.getItemMeta();
                    meta.setDisplayName(color("&6ClasherCity Selection Wand"));
                    meta.setLore(List.of(color("&7Left click block: position 1"), color("&7Right click block: position 2")));
                    meta.getPersistentDataContainer().set(keyWand, PersistentDataType.BYTE, (byte) 1);
                    wand.setItemMeta(meta);
                    p.getInventory().addItem(wand);
                    p.sendMessage(msg("Selection wand given. Select two corners in the city world."));
                }
                case "createplot" -> {
                    if (!(sender instanceof Player p) || args.length < 3) { sender.sendMessage("Usage: /clcity createplot <id> <price>"); break; }
                    double price = Double.parseDouble(args[2]);
                    createPlotFromSelection(p, args[1], price);
                }
                case "createroad" -> {
                    if (!(sender instanceof Player p) || args.length < 2) { sender.sendMessage("Usage: /clcity createroad <id>"); break; }
                    createAreaFromSelection(p, args[1], "ROAD");
                }
                case "border" -> {
                    if (!(sender instanceof Player p) || args.length < 3) { sender.sendMessage("Usage: /clcity border <plot-id> <preview|create>"); break; }
                    if (!args[2].equalsIgnoreCase("preview") && !args[2].equalsIgnoreCase("create")) { sender.sendMessage("Usage: /clcity border <plot-id> <preview|create>"); break; }
                    buildPlotBorder(p, args[1], args[2].equalsIgnoreCase("create"));
                }
                case "createpublic" -> {
                    if (!(sender instanceof Player p) || args.length < 2) { sender.sendMessage("Usage: /clcity createpublic <id>"); break; }
                    createAreaFromSelection(p, args[1], "PUBLIC");
                }
                case "roadbuild" -> {
                    if (!(sender instanceof Player p) || args.length < 3) { sender.sendMessage("Usage: /clcity roadbuild <road-id> <preview|create>"); break; }
                    if (!args[2].equalsIgnoreCase("preview") && !args[2].equalsIgnoreCase("create")) { sender.sendMessage("Usage: /clcity roadbuild <road-id> <preview|create>"); break; }
                    buildRoad(p, args[1], args[2].equalsIgnoreCase("create"));
                }
                case "parkbuild" -> {
                    if (!(sender instanceof Player p) || args.length < 3) { sender.sendMessage("Usage: /clcity parkbuild <public-area-id> <preview|create>"); break; }
                    if (!args[2].equalsIgnoreCase("preview") && !args[2].equalsIgnoreCase("create")) { sender.sendMessage("Usage: /clcity parkbuild <public-area-id> <preview|create>"); break; }
                    buildPark(p, args[1], args[2].equalsIgnoreCase("create"));
                }
                case "setprice" -> {
                    if (args.length < 3) { sender.sendMessage("Usage: /clcity setprice <plot-id> <price>"); break; }
                    double price = Double.parseDouble(args[2]);
                    if (price < 0 || !Double.isFinite(price)) throw new IllegalArgumentException("Price must be zero or greater.");
                    try (PreparedStatement ps = db.prepareStatement("UPDATE plots SET price=? WHERE id=?")) {
                        ps.setDouble(1, price); ps.setString(2, args[1]);
                        sender.sendMessage(msg(ps.executeUpdate() == 1 ? "Price updated." : "Plot not found."));
                    }
                }
                case "deleteplot" -> {
                    if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) { sender.sendMessage("Usage: /clcity deleteplot <id> confirm"); break; }
                    deletePlot(args[1]); sender.sendMessage(msg("Plot deleted: " + args[1]));
                }
                case "setlocation" -> {
                    if (!(sender instanceof Player p) || args.length < 2) { sender.sendMessage("Usage: /clcity setlocation <id>"); break; }
                    validateId(args[1]);
                    Location l=p.getLocation();
                    String path="destinations."+args[1].toLowerCase(Locale.ROOT);
                    getConfig().set(path+".world",l.getWorld().getName()); getConfig().set(path+".x",l.getX()); getConfig().set(path+".y",l.getY()); getConfig().set(path+".z",l.getZ());
                    getConfig().set(path+".yaw",l.getYaw()); getConfig().set(path+".pitch",l.getPitch()); saveConfig();
                    p.sendMessage(msg("City destination saved: &e"+args[1]));
                }
                case "removelocation" -> {
                    if(args.length<2){ sender.sendMessage("Usage: /clcity removelocation <id>"); break; }
                    getConfig().set("destinations."+args[1].toLowerCase(Locale.ROOT),null); saveConfig();
                    sender.sendMessage(msg("City destination removed: "+args[1]));
                }
                case "setspawn" -> {
                    if (!(sender instanceof Player p) || !isCityWorld(p.getWorld())) { sender.sendMessage("Stand inside the city world and run /clcity setspawn."); break; }
                    Location l = p.getLocation();
                    getConfig().set("city.spawn.x", l.getX()); getConfig().set("city.spawn.y", l.getY());
                    getConfig().set("city.spawn.z", l.getZ()); getConfig().set("city.spawn.yaw", l.getYaw());
                    getConfig().set("city.spawn.pitch", l.getPitch()); getConfig().set("city.spawn-set", true); saveConfig();
                    cityWorld.setSpawnLocation(l); sender.sendMessage(msg("City spawn saved."));
                }
                case "mapitem" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); break; }
                    givePhysicalMap(p);
                }
                case "undo" -> {
                    if(args.length<3 || !args[2].equalsIgnoreCase("confirm")) { sender.sendMessage("Usage: /clcity undo <operation-id> confirm"); break; }
                    undoWorldgen(sender,args[1]);
                }
                case "plotboard" -> {
                    if(!(sender instanceof Player p) || args.length<3) { sender.sendMessage("Usage: /clcity plotboard <plot-id> <preview|create>"); break; }
                    if(!args[2].equalsIgnoreCase("preview") && !args[2].equalsIgnoreCase("create")) { sender.sendMessage("Usage: /clcity plotboard <plot-id> <preview|create>"); break; }
                    buildPlotBoard(p,args[1],args[2].equalsIgnoreCase("create"));
                }
                case "ceremony" -> {
                    if(!(sender instanceof Player p) || args.length<2) { sender.sendMessage("Usage: /clcity ceremony <preview|start>"); break; }
                    if(!args[1].equalsIgnoreCase("preview") && !args[1].equalsIgnoreCase("start")) { sender.sendMessage("Usage: /clcity ceremony <preview|start>"); break; }
                    runWinnerCeremony(p,args[1].equalsIgnoreCase("start"));
                }
                case "mapwall" -> {
                    if (!(sender instanceof Player p) || args.length < 2) { sender.sendMessage("Usage: /clcity mapwall <select|preview|create|update|remove>"); break; }
                    String action=args[1].toLowerCase(Locale.ROOT);
                    if(action.equals("select")) {
                        Location l=p.getLocation().getBlock().getLocation();
                        getConfig().set("map-wall.world",l.getWorld().getName());
                        getConfig().set("map-wall.x",l.getBlockX()); getConfig().set("map-wall.y",l.getBlockY()); getConfig().set("map-wall.z",l.getBlockZ());
                        getConfig().set("map-wall.facing",p.getFacing().getOppositeFace().name()); saveConfig();
                        p.sendMessage(msg("Map wall anchor saved. Stand at the lower-left block of the 4x4 frame wall before selecting."));
                    } else if(action.equals("remove")) removeMapWall(p);
                    else if(action.equals("preview")) createMapWall(p,false);
                    else if(action.equals("create") || action.equals("update")) createMapWall(p,true);
                    else p.sendMessage(msg("Usage: /clcity mapwall <select|preview|create|update|remove>"));
                }
                case "start" -> startTournament(sender, args.length >= 2 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "ClasherCity Building Championship");
                case "phase" -> {
                    if (args.length < 2) { sender.sendMessage("Usage: /clcity phase <setup|registration|building|voting|results>"); break; }
                    Phase next = Phase.valueOf(args[1].toUpperCase(Locale.ROOT));
                    setPhase(next); broadcast(msg("Tournament phase changed to &e" + next.name() + "&a."));
                }
                case "end" -> closeTournament(sender);
                case "voting" -> { setPhase(Phase.VOTING); broadcast(msg("Voting is now open. Use &e/buildvote&a.")); }
                case "closevote" -> closeTournament(sender);
                case "judge" -> judgeCommand(sender, args);
                case "score" -> scoreCommand(sender, args);
                case "winner" -> winnerOverrideCommand(sender, args);
                case "results" -> showResults(sender);
                case "reward" -> rewardCommand(sender, args);
                case "reload" -> { reloadConfig(); sender.sendMessage(msg("Configuration reloaded.")); }
                case "info" -> showAdminInfo(sender);
                default -> sendAdminHelp(sender);
            }
        } catch (Exception ex) {
            sender.sendMessage(msg("Operation failed: " + ex.getMessage()));
            getLogger().warning("Admin command failed: " + ex);
        }
        return true;
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage(color("&6---- ClasherCity Admin ----"));
        sender.sendMessage(color("&e/clcity admin &7- Open admin GUI"));
        sender.sendMessage(color("&e/clcity create &7- Load/create flat city world"));
        sender.sendMessage(color("&e/clcity tp &7- Teleport to city spawn"));
        sender.sendMessage(color("&e/clcity wand &7- Get selection wand"));
        sender.sendMessage(color("&e/clcity createplot <id> <price> &7- Register a plot"));
        sender.sendMessage(color("&e/clcity createroad <id> &7- Protect a road selection"));
        sender.sendMessage(color("&e/clcity border <plot-id> <preview|create> &7- Preview/build a safe plot border"));
        sender.sendMessage(color("&e/clcity createpublic <id> &7- Protect public area"));
        sender.sendMessage(color("&e/clcity roadbuild <id> <preview|create> &7- Preview/build a modern road"));
        sender.sendMessage(color("&e/clcity parkbuild <id> <preview|create> &7- Preview/build a public park"));
        sender.sendMessage(color("&e/clcity setprice <id> <price> &7- Change plot price"));
        sender.sendMessage(color("&e/clcity deleteplot <id> confirm &7- Delete an unowned plot"));
        sender.sendMessage(color("&e/clcity setspawn &7- Save city spawn"));
        sender.sendMessage(color("&e/clcity setlocation <id> &7- Register a teleport destination"));
        sender.sendMessage(color("&e/clcity removelocation <id> &7- Remove a destination"));
        sender.sendMessage(color("&e/clcity mapitem &7- Give physical map"));
        sender.sendMessage(color("&e/clcity mapwall <select|preview|create|update|remove> &7- Manage 4x4 city map wall"));
        sender.sendMessage(color("&e/clcity undo <operation-id> confirm &7- Safely undo plugin-generated blocks"));
        sender.sendMessage(color("&e/clcity ceremony <preview|start> &7- Build podium and announce top 3"));
        sender.sendMessage(color("&e/clcity plotboard <id> <preview|create> &7- Place a live plot information board"));
        sender.sendMessage(color("&e/clcity start [name] &7- Start a tournament"));
        sender.sendMessage(color("&e/clcity phase <phase> &7- Change tournament phase"));
        sender.sendMessage(color("&e/clcity judge <add|remove> <player> &7- Manage judges"));
        sender.sendMessage(color("&e/clcity score <plot> <category> <0..max> &7- Submit judge score"));
        sender.sendMessage(color("&e/clcity winner <1|2|3> <plot|auto> &7- Override a podium position"));
        sender.sendMessage(color("&e/clcity closevote &7- Close voting, calculate and reward"));
        sender.sendMessage(color("&e/clcity results &7- Show final results"));
        sender.sendMessage(color("&e/clcity reward distribute &7- Queue configured rewards"));
        sender.sendMessage(color("&e/clcity reward retry <player> &7- Retry pending reward"));
    }

    private void showAdminInfo(CommandSender sender) throws SQLException {
        sender.sendMessage(color("&6ClasherCity &7| World: &f" + (cityWorld == null ? "not loaded" : cityWorld.getName())
                + " &7| Phase: &f" + phase() + " &7| Plots: &f" + getPlots().size()
                + " &7| Areas: &f" + getAreas().size() + " &7| Economy: &f" + economy.getName()));
    }

    private void createPlotFromSelection(Player player, String id, double price) throws Exception {
        if (!isCityWorld(player.getWorld())) throw new IllegalArgumentException("You must be in the city world.");
        if (price < 0 || !Double.isFinite(price)) throw new IllegalArgumentException("Price must be zero or greater.");
        validateId(id);
        Location[] pair = selections.get(player.getUniqueId());
        if (pair == null || pair[0] == null || pair[1] == null) throw new IllegalArgumentException("Select two corners with the wand first.");
        if (!isCityWorld(pair[0].getWorld()) || !isCityWorld(pair[1].getWorld())) throw new IllegalArgumentException("Both positions must be in the city world.");
        int minX = Math.min(pair[0].getBlockX(), pair[1].getBlockX());
        int maxX = Math.max(pair[0].getBlockX(), pair[1].getBlockX());
        int minZ = Math.min(pair[0].getBlockZ(), pair[1].getBlockZ());
        int maxZ = Math.max(pair[0].getBlockZ(), pair[1].getBlockZ());
        int width = maxX - minX + 1, depth = maxZ - minZ + 1;
        if (width < getConfig().getInt("plots.minimum-size", 10) || depth < getConfig().getInt("plots.minimum-size", 10))
            throw new IllegalArgumentException("Plot is smaller than the configured minimum size.");
        if (plotExists(id)) throw new IllegalArgumentException("A plot with this ID already exists.");
        if (overlapsAny(minX, minZ, maxX, maxZ)) throw new IllegalArgumentException("This selection overlaps another plot, road, or public area.");
        Plot plot = new Plot(id, cityWorld.getName(), minX, minZ, maxX, maxZ, price, null, null);
        RegionManager manager = regionManager(cityWorld);
        if (manager == null) throw new IllegalStateException("WorldGuard region manager unavailable.");
        if (manager.hasRegion(plotRegionId(id))) throw new IllegalArgumentException("WorldGuard region ID already exists.");
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO plots(id,world,min_x,min_z,max_x,max_z,price) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, cityWorld.getName()); ps.setInt(3, minX); ps.setInt(4, minZ);
            ps.setInt(5, maxX); ps.setInt(6, maxZ); ps.setDouble(7, price); ps.executeUpdate();
        }
        try {
            getOrCreatePlotRegion(plot);
            manager.saveChanges();
        } catch (Exception ex) {
            try (PreparedStatement ps = db.prepareStatement("DELETE FROM plots WHERE id=?")) { ps.setString(1, id); ps.executeUpdate(); }
            manager.removeRegion(plotRegionId(id));
            throw ex;
        }
        mapRenderer.invalidate();
        player.sendMessage(msg("Plot &e" + id + "&a created (&f" + width + "x" + depth + "&a) for &e" + economy.format(price) + "&a."));
    }

    private void createAreaFromSelection(Player player, String id, String type) throws Exception {
        if (!isCityWorld(player.getWorld())) throw new IllegalArgumentException("You must be in the city world.");
        validateId(id);
        Location[] pair = selections.get(player.getUniqueId());
        if (pair == null || pair[0] == null || pair[1] == null) throw new IllegalArgumentException("Select two corners with the wand first.");
        int minX = Math.min(pair[0].getBlockX(), pair[1].getBlockX());
        int maxX = Math.max(pair[0].getBlockX(), pair[1].getBlockX());
        int minZ = Math.min(pair[0].getBlockZ(), pair[1].getBlockZ());
        int maxZ = Math.max(pair[0].getBlockZ(), pair[1].getBlockZ());
        if (overlapsAny(minX, minZ, maxX, maxZ)) throw new IllegalArgumentException("This area overlaps another plot, road, or public area.");
        String regionId = areaRegionId(type, id);
        RegionManager manager = regionManager(cityWorld);
        if (manager == null || manager.hasRegion(regionId)) throw new IllegalArgumentException("WorldGuard region already exists or is unavailable.");
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO areas(id,world,type,min_x,min_z,max_x,max_z) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, cityWorld.getName()); ps.setString(3, type);
            ps.setInt(4, minX); ps.setInt(5, minZ); ps.setInt(6, maxX); ps.setInt(7, maxZ); ps.executeUpdate();
        }
        try {
            getOrCreateAreaRegion(new Area(id, cityWorld.getName(), type, minX, minZ, maxX, maxZ));
            manager.saveChanges();
        } catch (Exception ex) {
            try (PreparedStatement ps = db.prepareStatement("DELETE FROM areas WHERE id=? AND type=?")) { ps.setString(1, id); ps.setString(2, type); ps.executeUpdate(); }
            manager.removeRegion(regionId);
            throw ex;
        }
        mapRenderer.invalidate();
        player.sendMessage(msg(type + " area &e" + id + "&a created and protected."));
    }

    private void buildPlotBorder(Player player, String plotId, boolean create) throws SQLException {
        if (cityWorld == null || !isCityWorld(player.getWorld())) throw new IllegalArgumentException("You must be in the city world.");
        Plot plot = getPlot(plotId);
        if (plot == null) throw new IllegalArgumentException("Plot not found: " + plotId);
        int height = Math.max(2, Math.min(5, getConfig().getInt("plot-border.height", 3)));
        int entranceWidth = Math.max(1, Math.min(7, getConfig().getInt("plot-border.entrance-width", 3)));
        int entranceStart = (plot.minX() + plot.maxX() - entranceWidth + 1) / 2;
        int entranceEnd = entranceStart + entranceWidth - 1;
        int eligible = 0, skipped = 0, changed = 0;
        String operationId=UUID.randomUUID().toString().substring(0,8);
        List<String[]> changes=new ArrayList<>();
        for (int x = plot.minX(); x <= plot.maxX(); x++) {
            for (int z = plot.minZ(); z <= plot.maxZ(); z++) {
                boolean edge = x == plot.minX() || x == plot.maxX() || z == plot.minZ() || z == plot.maxZ();
                if (!edge) continue;
                boolean corner = (x == plot.minX() || x == plot.maxX()) && (z == plot.minZ() || z == plot.maxZ());
                boolean entrance = z == plot.maxZ() && x >= entranceStart && x <= entranceEnd && !corner;
                if (entrance) continue;
                int groundY = cityWorld.getHighestBlockYAt(x, z);
                for (int dy = 1; dy <= height; dy++) {
                    Material material = corner ? Material.STONE_BRICKS : (dy == 1 ? Material.STONE_BRICK_WALL : Material.IRON_BARS);
                    if (!corner && dy == height) material = Material.STONE_BRICK_WALL;
                    var block = cityWorld.getBlockAt(x, groundY + dy, z);
                    if (block.getType() == Material.AIR || block.isPassable()) eligible++;
                    else skipped++;
                    if (create && (block.getType() == Material.AIR || block.isPassable())) {
                        placeGeneratedBlock(changes,block,material);
                        changed++;
                    }
                }
            }
        }
        if (!create) {
            player.sendMessage(msg("Border preview for &e" + plot.id() + "&a: &f" + eligible + " &ablocks can be placed, &c" + skipped + " &awill be skipped. Entrance: &f" + entranceWidth + " blocks."));
        } else {
            persistWorldgenOperation(operationId,player,"PLOT_BORDER",changes);
            player.sendMessage(msg("Border built for &e" + plot.id() + "&a. Placed &f" + changed + " &ablocks; safely skipped &c" + skipped + "&a occupied blocks. Undo ID: &e"+operationId));
        }
    }

    private void buildPark(Player player, String areaId, boolean create) throws SQLException {
        if (cityWorld == null || !isCityWorld(player.getWorld())) throw new IllegalArgumentException("You must be in the city world.");
        Area area = getAreas().stream().filter(a -> a.type().equalsIgnoreCase("PUBLIC") && a.id().equalsIgnoreCase(areaId)).findFirst().orElse(null);
        if (area == null) throw new IllegalArgumentException("Public area not found: " + areaId + ". Register it with /clcity createpublic first.");
        long areaSize = (long)(area.maxX()-area.minX()+1)*(area.maxZ()-area.minZ()+1);
        long maxBlocks = getConfig().getLong("park-builder.max-area-blocks", 20000L);
        if (areaSize > maxBlocks) throw new IllegalArgumentException("Park area exceeds configured safety limit of " + maxBlocks + " blocks.");
        int minX=area.minX(), maxX=area.maxX(), minZ=area.minZ(), maxZ=area.maxZ();
        int midX=(minX+maxX)/2, midZ=(minZ+maxZ)/2;
        int changed=0, skipped=0, eligible=0;
        String operationId=UUID.randomUUID().toString().substring(0,8);
        List<String[]> changes=new ArrayList<>();
        for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++) {
            boolean path = Math.abs(x-midX)<=1 || Math.abs(z-midZ)<=1;
            int y=cityWorld.getHighestBlockYAt(x,z)+1;
            var block=cityWorld.getBlockAt(x,y,z);
            if(block.getType()!=Material.AIR && !block.isPassable()){ skipped++; continue; }
            eligible++;
            if(create){ placeGeneratedBlock(changes,block,path ? Material.POLISHED_ANDESITE : Material.GRASS_BLOCK); changed++; }
        }
        int lampCount=0;
        int[][] corners={{minX+2,minZ+2},{minX+2,maxZ-2},{maxX-2,minZ+2},{maxX-2,maxZ-2}};
        for(int[] pos:corners){
            if(pos[0]<minX||pos[0]>maxX||pos[1]<minZ||pos[1]>maxZ) continue;
            int y=cityWorld.getHighestBlockYAt(pos[0],pos[1])+1;
            boolean clear=true;
            for(int dy=0;dy<4;dy++) if(!cityWorld.getBlockAt(pos[0],y+dy,pos[1]).isPassable()) clear=false;
            if(create&&clear){
                placeGeneratedBlock(changes,cityWorld.getBlockAt(pos[0],y,pos[1]),Material.OAK_FENCE);
                placeGeneratedBlock(changes,cityWorld.getBlockAt(pos[0],y+1,pos[1]),Material.OAK_FENCE);
                placeGeneratedBlock(changes,cityWorld.getBlockAt(pos[0],y+2,pos[1]),Material.LANTERN);
                lampCount++;
            }
        }
        if(create) { persistWorldgenOperation(operationId,player,"PARK",changes); player.sendMessage(msg("Park &e"+area.id()+"&a generated: &f"+changed+"&a ground blocks and &f"+lampCount+"&a lamps; occupied blocks skipped: &c"+skipped+". Undo ID: &e"+operationId)); }
        else player.sendMessage(msg("Park preview &e"+area.id()+"&a: &f"+eligible+"&a clear ground blocks, &c"+skipped+"&a occupied; up to 4 lamp locations."));
    }

    private void buildRoad(Player player, String roadId, boolean create) throws SQLException {
        if (cityWorld == null || !isCityWorld(player.getWorld())) throw new IllegalArgumentException("You must be in the city world.");
        Area area = getAreas().stream().filter(a -> a.type().equalsIgnoreCase("ROAD") && a.id().equalsIgnoreCase(roadId)).findFirst().orElse(null);
        if (area == null) throw new IllegalArgumentException("Protected road area not found: " + roadId);
        long areaSize = (long) (area.maxX() - area.minX() + 1) * (area.maxZ() - area.minZ() + 1);
        long maxBlocks = getConfig().getLong("road-builder.max-area-blocks", 30000L);
        if (areaSize > maxBlocks) throw new IllegalArgumentException("Road area is " + areaSize + " blocks; configured safety limit is " + maxBlocks + ".");
        boolean alongX = (area.maxX() - area.minX()) >= (area.maxZ() - area.minZ());
        int length = alongX ? area.maxX() - area.minX() + 1 : area.maxZ() - area.minZ() + 1;
        int width = alongX ? area.maxZ() - area.minZ() + 1 : area.maxX() - area.minX() + 1;
        if (width < 7) throw new IllegalArgumentException("Road selection must be at least 7 blocks wide to fit asphalt, lane markings and sidewalks.");
        int sidewalkWidth = Math.max(1, Math.min(2, getConfig().getInt("road-builder.sidewalk-width", 2)));
        int lampSpacing = Math.max(6, getConfig().getInt("road-builder.streetlight-spacing", 12));
        int center = width / 2;
        int eligible = 0, skipped = 0, changed = 0;
        String operationId=UUID.randomUUID().toString().substring(0,8);
        List<String[]> changes=new ArrayList<>();
        for (int along = 0; along < length; along++) {
            for (int across = 0; across < width; across++) {
                int x = alongX ? area.minX() + along : area.minX() + across;
                int z = alongX ? area.minZ() + across : area.minZ() + along;
                boolean sidewalk = across < sidewalkWidth || across >= width - sidewalkWidth;
                boolean curb = across == sidewalkWidth || across == width - sidewalkWidth - 1;
                boolean laneMark = across == center && (along % 8 < 4);
                Material surface = sidewalk ? Material.LIGHT_GRAY_CONCRETE : (curb ? Material.GRAY_CONCRETE : (laneMark ? Material.WHITE_CONCRETE : Material.BLACK_CONCRETE));
                int y = cityWorld.getHighestBlockYAt(x, z) + 1;
                var block = cityWorld.getBlockAt(x, y, z);
                if (block.getType() == Material.AIR || block.isPassable()) {
                    eligible++;
                    if (create) { placeGeneratedBlock(changes,block,surface); changed++; }
                } else skipped++;
            }
        }
        int lampCandidates = 0, lampsPlaced = 0;
        if (width >= 9) {
            for (int along = 0; along < length; along += lampSpacing) {
                for (int side : new int[]{sidewalkWidth - 1, width - sidewalkWidth}) {
                    int x = alongX ? area.minX() + along : area.minX() + side;
                    int z = alongX ? area.minZ() + side : area.minZ() + along;
                    int baseY = cityWorld.getHighestBlockYAt(x, z) + 1;
                    boolean clear = true;
                    for (int dy = 0; dy < 4; dy++) if (!cityWorld.getBlockAt(x, baseY + dy, z).isPassable()) clear = false;
                    lampCandidates++;
                    if (create && clear) {
                        placeGeneratedBlock(changes,cityWorld.getBlockAt(x,baseY,z),Material.STONE_BRICK_WALL);
                        placeGeneratedBlock(changes,cityWorld.getBlockAt(x,baseY+1,z),Material.STONE_BRICK_WALL);
                        placeGeneratedBlock(changes,cityWorld.getBlockAt(x,baseY+2,z),Material.LANTERN);
                        lampsPlaced++;
                    }
                }
            }
        }
        if (create) { persistWorldgenOperation(operationId,player,"ROAD",changes); player.sendMessage(msg("Road &e" + roadId + "&a generated: &f" + changed + "&a surface blocks, &f" + lampsPlaced + "&a streetlights; &c" + skipped + "&a occupied surface blocks skipped. Undo ID: &e"+operationId)); }
        else player.sendMessage(msg("Road preview &e" + roadId + "&a: &f" + eligible + "&a surface blocks available, &c" + skipped + "&a occupied; &f" + lampCandidates + "&a streetlight positions checked."));
    }

    private void buildPlotBoard(Player player,String plotId,boolean create) throws SQLException {
        if(cityWorld==null || !isCityWorld(player.getWorld())) throw new IllegalArgumentException("You must be in the city world.");
        Plot plot=getPlot(plotId);
        if(plot==null) throw new IllegalArgumentException("Plot not found.");
        int x=(plot.minX()+plot.maxX())/2, z=plot.maxZ()+1, y=cityWorld.getHighestBlockYAt(x,z)+1;
        org.bukkit.block.Block block=cityWorld.getBlockAt(x,y,z);
        if(!block.getType().isAir() && !block.isPassable()) throw new IllegalArgumentException("Plot board position is occupied. Clear the centre of the front edge first.");
        if(!create){ player.sendMessage(msg("Plot board preview for &e"+plotId+"&a: sign at &f"+x+","+y+","+z+"&a. It will show price, availability, owner and tournament entry status.")); return; }
        String opId=UUID.randomUUID().toString().substring(0,8);
        List<String[]> changes=new ArrayList<>();
        placeGeneratedBlock(changes,block,Material.OAK_SIGN);
        if(block.getState() instanceof org.bukkit.block.Sign sign){
            sign.setLine(0,color("&b&lCLASHERCITY"));
            sign.setLine(1,color("&f"+plot.id()+" &e"+economy.format(plot.price())));
            sign.setLine(2,color(plot.ownerUuid()==null?"&aAVAILABLE":"&cSOLD"));
            sign.setLine(3,color(plot.ownerUuid()==null?"&7No owner":("&f"+plot.ownerName())));
            sign.update(true,false);
        }
        try(PreparedStatement ps=db.prepareStatement("INSERT INTO plot_boards(plot_id,world,x,y,z) VALUES(?,?,?,?,?) ON CONFLICT(plot_id) DO UPDATE SET world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z")){
            ps.setString(1,plot.id()); ps.setString(2,cityWorld.getName()); ps.setInt(3,x); ps.setInt(4,y); ps.setInt(5,z); ps.executeUpdate();
        }
        persistWorldgenOperation(opId,player,"PLOT_BOARD",changes);
        refreshPlotBoard(plot.id());
        player.sendMessage(msg("Live plot board placed for &e"+plotId+"&a. Undo ID: &e"+opId));
    }

    private void refreshPlotBoard(String plotId) {
        try(PreparedStatement ps=db.prepareStatement("SELECT world,x,y,z FROM plot_boards WHERE plot_id=?")){
            ps.setString(1,plotId);
            try(ResultSet rs=ps.executeQuery()){
                if(!rs.next()) return;
                World w=Bukkit.getWorld(rs.getString("world")); if(w==null) return;
                org.bukkit.block.Block block=w.getBlockAt(rs.getInt("x"),rs.getInt("y"),rs.getInt("z"));
                if(!(block.getState() instanceof org.bukkit.block.Sign sign)) return;
                Plot plot=getPlot(plotId); if(plot==null) return;
                boolean entered=false;
                try(PreparedStatement entry=db.prepareStatement("SELECT 1 FROM tournament_entries WHERE tournament_id=? AND plot_id=?")){
                    entry.setString(1,getMeta("tournament_id")); entry.setString(2,plotId);
                    try(ResultSet e=entry.executeQuery()){ entered=e.next(); }
                }
                sign.setLine(0,color("&b&lCLASHERCITY"));
                sign.setLine(1,color("&f"+plot.id()+" &e"+economy.format(plot.price())));
                sign.setLine(2,color(plot.ownerUuid()==null?"&aAVAILABLE":"&cSOLD"));
                sign.setLine(3,color(entered?"&dIN TOURNAMENT":(plot.ownerUuid()==null?"&7No owner":"&f"+plot.ownerName())));
                sign.update(true,false);
            }
        } catch(Exception ex){ getLogger().warning("Could not refresh plot board "+plotId+": "+ex.getMessage()); }
    }

    private void runWinnerCeremony(Player admin,boolean start) throws SQLException {
        if(cityWorld==null || !isCityWorld(admin.getWorld())) throw new IllegalArgumentException("Stand in the city world to place the winner podium.");
        List<ResultRow> results=calculateResults();
        if(results.isEmpty()) throw new IllegalArgumentException("No tournament results are available yet.");
        Location origin=admin.getLocation();
        int baseX=origin.getBlockX(), baseZ=origin.getBlockZ();
        String operationId=UUID.randomUUID().toString().substring(0,8);
        List<String[]> changes=new ArrayList<>();
        Material[] podiumMaterials={Material.GOLD_BLOCK,Material.IRON_BLOCK,Material.COPPER_BLOCK};
        int[] offsets={-3,0,3};
        int[] heights={3,2,1};
        int eligible=0,skipped=0;
        for(int rank=0;rank<Math.min(3,results.size());rank++){
            int x=baseX+offsets[rank], z=baseZ;
            int ground=cityWorld.getHighestBlockYAt(x,z);
            boolean clear=true;
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) for(int dy=1;dy<=heights[rank];dy++)
                if(!cityWorld.getBlockAt(x+dx,ground+dy,z+dz).isPassable()) clear=false;
            if(!clear){ skipped++; continue; }
            eligible++;
            if(start){
                for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++){
                    for(int dy=1;dy<=heights[rank];dy++){
                        Material m=(dx==0&&dz==0&&dy==heights[rank])?podiumMaterials[rank]:Material.STONE_BRICKS;
                        placeGeneratedBlock(changes,cityWorld.getBlockAt(x+dx,ground+dy,z+dz),m);
                    }
                }
                Location fireworkLoc=new Location(cityWorld,x+0.5,ground+heights[rank]+3,z+0.5);
                org.bukkit.entity.Firework firework=cityWorld.spawn(fireworkLoc,org.bukkit.entity.Firework.class);
                org.bukkit.inventory.meta.FireworkMeta meta=firework.getFireworkMeta();
                meta.addEffect(org.bukkit.FireworkEffect.builder().with(org.bukkit.FireworkEffect.Type.BALL_LARGE).withColor(java.awt.Color.ORANGE,java.awt.Color.AQUA).trail(true).flicker(true).build());
                meta.setPower(1); firework.setFireworkMeta(meta);
            }
        }
        if(!start){ admin.sendMessage(msg("Winner podium preview: &f"+eligible+"&a of top 3 positions are clear; &c"+skipped+"&a are obstructed.")); return; }
        persistWorldgenOperation(operationId,admin,"WINNER_PODIUM",changes);
        broadcast(color("&6&l✦ CLASHERCITY CHAMPIONS ✦"));
        for(int i=0;i<Math.min(3,results.size());i++){
            ResultRow row=results.get(i);
            broadcast(color((i==0?"&6&l1ST":"&f"+(i+1)+"ND")+" &e"+row.ownerName()+" &7— Plot &f"+row.plotId()+" &8| &b"+String.format(Locale.US,"%.1f",row.finalScore())));
        }
        for(Player online:Bukkit.getOnlinePlayers()) online.sendTitle(color("&6&lCLASHERCITY"),color("&eThe champions have been crowned!"),10,80,20);
        admin.sendMessage(msg("Winner ceremony launched. Undo podium blocks with &e/clcity undo "+operationId+" confirm"));
    }

    private void placeGeneratedBlock(List<String[]> changes, org.bukkit.block.Block block, Material material) {
        if(block.getType()!=Material.AIR && !block.isPassable()) return;
        String oldData=block.getBlockData().getAsString();
        block.setType(material,false);
        changes.add(new String[]{block.getWorld().getName(),Integer.toString(block.getX()),Integer.toString(block.getY()),Integer.toString(block.getZ()),oldData,block.getBlockData().getAsString()});
    }

    private void persistWorldgenOperation(String id,Player player,String kind,List<String[]> changes) {
        if(changes.isEmpty()) return;
        try {
            db.setAutoCommit(false);
            try(PreparedStatement op=db.prepareStatement("INSERT INTO worldgen_operations(id,player_uuid,player_name,kind) VALUES(?,?,?,?)");
                PreparedStatement ps=db.prepareStatement("INSERT INTO worldgen_changes(operation_id,world,x,y,z,old_data,new_data) VALUES(?,?,?,?,?,?,?)")){
                op.setString(1,id); op.setString(2,player.getUniqueId().toString()); op.setString(3,player.getName()); op.setString(4,kind); op.executeUpdate();
                for(String[] c:changes){
                    ps.setString(1,id); ps.setString(2,c[0]); ps.setInt(3,Integer.parseInt(c[1])); ps.setInt(4,Integer.parseInt(c[2])); ps.setInt(5,Integer.parseInt(c[3])); ps.setString(6,c[4]); ps.setString(7,c[5]); ps.addBatch();
                }
                ps.executeBatch(); db.commit();
            } catch(Exception ex){ db.rollback(); throw ex; }
            finally { db.setAutoCommit(true); }
        } catch(Exception ex){ getLogger().severe("Could not save worldgen undo data for "+id+": "+ex.getMessage()); player.sendMessage(msg("WARNING: build completed but undo snapshot could not be saved. Contact an admin; operation "+id)); }
    }

    private void undoWorldgen(CommandSender sender,String id) throws SQLException {
        try(PreparedStatement check=db.prepareStatement("SELECT status FROM worldgen_operations WHERE id=?")){
            check.setString(1,id);
            try(ResultSet rs=check.executeQuery()){
                if(!rs.next()){ sender.sendMessage(msg("Unknown worldgen operation ID.")); return; }
                if(!rs.getString("status").equals("ACTIVE")){ sender.sendMessage(msg("This operation has already been undone or closed.")); return; }
            }
        }
        int restored=0,skipped=0;
        try(PreparedStatement ps=db.prepareStatement("SELECT rowid,world,x,y,z,old_data,new_data FROM worldgen_changes WHERE operation_id=? AND restored=0")){
            ps.setString(1,id);
            try(ResultSet rs=ps.executeQuery()){
                while(rs.next()){
                    World w=Bukkit.getWorld(rs.getString("world"));
                    if(w==null){ skipped++; continue; }
                    org.bukkit.block.Block block=w.getBlockAt(rs.getInt("x"),rs.getInt("y"),rs.getInt("z"));
                    if(!block.getBlockData().getAsString().equals(rs.getString("new_data"))){ skipped++; continue; }
                    block.setBlockData(Bukkit.createBlockData(rs.getString("old_data")),false);
                    try(PreparedStatement mark=db.prepareStatement("UPDATE worldgen_changes SET restored=1 WHERE rowid=?")){ mark.setLong(1,rs.getLong("rowid")); mark.executeUpdate(); }
                    restored++;
                }
            }
        }
        try(PreparedStatement ps=db.prepareStatement("UPDATE worldgen_operations SET status='UNDONE' WHERE id=?")){ ps.setString(1,id); ps.executeUpdate(); }
        sender.sendMessage(msg("Undo complete for &e"+id+"&a: restored &f"+restored+"&a blocks; &c"+skipped+"&a skipped because they changed or their world is unloaded."));
    }

    private void validateId(String id) {
        if (id == null || id.length() > 32 || !id.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("ID must be 1-32 chars and use letters, numbers, _ or -.");
    }

    private boolean plotExists(String id) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM plots WHERE lower(id)=lower(?)")) {
            ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private boolean overlapsAny(int minX, int minZ, int maxX, int maxZ) throws SQLException {
        String sql = "SELECT 1 FROM plots WHERE world=? AND NOT(max_x<? OR min_x>? OR max_z<? OR min_z>?) "
                + "UNION ALL SELECT 1 FROM areas WHERE world=? AND NOT(max_x<? OR min_x>? OR max_z<? OR min_z>?) LIMIT 1";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, cityWorld.getName()); ps.setInt(2, minX - 1); ps.setInt(3, maxX + 1); ps.setInt(4, minZ - 1); ps.setInt(5, maxZ + 1);
            ps.setString(6, cityWorld.getName()); ps.setInt(7, minX - 1); ps.setInt(8, maxX + 1); ps.setInt(9, minZ - 1); ps.setInt(10, maxZ + 1);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private void deletePlot(String id) throws Exception {
        Plot plot = getPlot(id);
        if (plot == null) throw new IllegalArgumentException("Plot not found.");
        if (plot.ownerUuid() != null) throw new IllegalArgumentException("Owned plots cannot be deleted. Ask the owner to abandon it first.");
        RegionManager manager = regionManager(cityWorld);
        String regionId = plotRegionId(id);
        ProtectedRegion removedRegion = manager == null ? null : manager.getRegion(regionId);
        if (manager == null || removedRegion == null) throw new IllegalStateException("Plot WorldGuard region is missing.");
        try {
            db.setAutoCommit(false);
            try (PreparedStatement entry = db.prepareStatement("DELETE FROM tournament_entries WHERE plot_id=?")) {
                entry.setString(1, id); entry.executeUpdate();
            }
            try (PreparedStatement ps = db.prepareStatement("DELETE FROM plots WHERE id=? AND owner_uuid IS NULL")) {
                ps.setString(1, id);
                if (ps.executeUpdate() != 1) throw new SQLException("Plot delete did not affect exactly one unowned plot.");
            }
            manager.removeRegion(regionId);
            manager.saveChanges();
            db.commit();
        } catch (Exception ex) {
            try { db.rollback(); } catch (SQLException ignored) { }
            if (!manager.hasRegion(regionId)) {
                manager.addRegion(removedRegion);
                try { manager.saveChanges(); } catch (Exception restoreEx) { getLogger().severe("Could not restore WorldGuard region " + regionId + ": " + restoreEx.getMessage()); }
            }
            throw ex;
        } finally {
            try { db.setAutoCommit(true); } catch (SQLException ignored) { }
        }
        mapRenderer.invalidate();
    }

    private List<Plot> getPlots() throws SQLException {
        List<Plot> list = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("SELECT * FROM plots ORDER BY id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(new Plot(rs.getString("id"), rs.getString("world"), rs.getInt("min_x"), rs.getInt("min_z"),
                    rs.getInt("max_x"), rs.getInt("max_z"), rs.getDouble("price"), rs.getString("owner_uuid"), rs.getString("owner_name")));
        }
        return list;
    }

    private List<Plot> getTournamentPlots() throws SQLException {
        List<Plot> list = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("SELECT p.id,p.world,p.min_x,p.min_z,p.max_x,p.max_z,p.price,e.owner_uuid,e.owner_name FROM plots p INNER JOIN tournament_entries e ON e.plot_id=p.id WHERE e.tournament_id=? ORDER BY p.id")) {
            ps.setString(1, tournamentId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new Plot(rs.getString("id"), rs.getString("world"), rs.getInt("min_x"), rs.getInt("min_z"),
                        rs.getInt("max_x"), rs.getInt("max_z"), rs.getDouble("price"), rs.getString("owner_uuid"), rs.getString("owner_name")));
            }
        }
        return list;
    }

    private boolean isTournamentEntry(String plotId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM tournament_entries WHERE tournament_id=? AND plot_id=?")) {
            ps.setString(1, tournamentId()); ps.setString(2, plotId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private List<Area> getAreas() throws SQLException {
        List<Area> list = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("SELECT * FROM areas ORDER BY id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(new Area(rs.getString("id"), rs.getString("world"), rs.getString("type"), rs.getInt("min_x"),
                    rs.getInt("min_z"), rs.getInt("max_x"), rs.getInt("max_z")));
        }
        return list;
    }

    private Plot getPlot(String id) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT * FROM plots WHERE lower(id)=lower(?)")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Plot(rs.getString("id"), rs.getString("world"), rs.getInt("min_x"), rs.getInt("min_z"), rs.getInt("max_x"),
                        rs.getInt("max_z"), rs.getDouble("price"), rs.getString("owner_uuid"), rs.getString("owner_name"));
            }
        }
    }

    private Plot plotAt(Location location) {
        if (!isCityWorld(location.getWorld())) return null;
        try (PreparedStatement ps = db.prepareStatement("SELECT * FROM plots WHERE world=? AND min_x<=? AND max_x>=? AND min_z<=? AND max_z>=? LIMIT 1")) {
            int x = location.getBlockX(), z = location.getBlockZ();
            ps.setString(1, location.getWorld().getName()); ps.setInt(2, x); ps.setInt(3, x); ps.setInt(4, z); ps.setInt(5, z);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Plot(rs.getString("id"), rs.getString("world"), rs.getInt("min_x"), rs.getInt("min_z"), rs.getInt("max_x"),
                        rs.getInt("max_z"), rs.getDouble("price"), rs.getString("owner_uuid"), rs.getString("owner_name"));
            }
        } catch (SQLException ex) {
            getLogger().warning("Plot lookup failed: " + ex.getMessage());
            return null;
        }
    }

    private boolean isPlotOwner(Player player, Location location) {
        Plot plot = plotAt(location);
        return plot != null && plot.ownerUuid() != null && plot.ownerUuid().equals(player.getUniqueId().toString());
    }

    private boolean mayBuild(Player player, Location location) {
        if (player.hasPermission("clashercity.bypass")) return true;
        if (!isCityWorld(location.getWorld())) return true;
        if (phase() != Phase.BUILDING) return false;
        Plot plot = plotAt(location);
        if (plot == null || plot.ownerUuid() == null || !plot.ownerUuid().equals(player.getUniqueId().toString())) return false;
        try {
            return isTournamentEntry(plot.id());
        } catch (SQLException ex) {
            getLogger().warning("Could not verify tournament entry for " + plot.id() + ": " + ex.getMessage());
            return false;
        }
    }

    private void openPurchaseConfirm(Player player, String id) {
        Plot plot;
        try { plot = getPlot(id); }
        catch (SQLException ex) { player.sendMessage(msg("Database error.")); return; }
        if (plot == null) { player.sendMessage(msg("Plot not found.")); return; }
        if (plot.ownerUuid() != null) { player.sendMessage(msg("That plot is already owned by " + plot.ownerName() + ".")); return; }
        if (phase() != Phase.REGISTRATION) { player.sendMessage(msg("Plot purchases are only open during registration.")); return; }
        MenuHolder holder = new MenuHolder("CONFIRM", 0);
        Inventory inv = holder.create(27, color("&8Confirm plot purchase"));
        inv.setItem(11, menuItem(Material.LIME_CONCRETE, "&aConfirm purchase", List.of("&7Plot: &f" + plot.id(), "&7Price: &e" + economy.format(plot.price())), "buy", plot.id()));
        inv.setItem(15, menuItem(Material.RED_CONCRETE, "&cCancel", List.of("&7Return to plot browser"), "cancel", plot.id()));
        inv.setItem(13, menuItem(Material.MAP, "&ePlot " + plot.id(), List.of("&7Size: " + (plot.maxX() - plot.minX() + 1) + " x " + (plot.maxZ() - plot.minZ() + 1), "&7Price: &e" + economy.format(plot.price())), "info", plot.id()));
        player.openInventory(inv);
    }

    private void purchasePlot(Player player, String id) {
        if (phase() != Phase.REGISTRATION) { player.sendMessage(msg("Plot purchases are closed.")); return; }
        try {
            db.setAutoCommit(false);
            Plot plot = getPlot(id);
            if (plot == null) { db.rollback(); player.sendMessage(msg("Plot not found.")); return; }
            if (plot.ownerUuid() != null) { db.rollback(); player.sendMessage(msg("That plot is already sold.")); return; }
            int maxPlots = getConfig().getInt("plots.max-per-player", 2);
            if (!getConfig().getBoolean("plots.allow-multiple", true)) maxPlots = 1;
            try (PreparedStatement ps = db.prepareStatement("SELECT COUNT(*) FROM plots WHERE owner_uuid=?")) {
                ps.setString(1, player.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) { if (rs.next() && rs.getInt(1) >= maxPlots) { db.rollback(); player.sendMessage(msg("You have reached your plot limit of " + maxPlots + ".")); return; } }
            }
            RegionManager manager = regionManager(cityWorld);
            ProtectedRegion region = manager == null ? null : manager.getRegion(plotRegionId(plot.id()));
            if (region == null) { db.rollback(); player.sendMessage(msg("Plot protection is missing. Contact an admin.")); return; }
            if (!economy.has(player, plot.price())) { db.rollback(); player.sendMessage(msg("Insufficient balance. Required: " + economy.format(plot.price()))); return; }
            var response = economy.withdrawPlayer(player, plot.price());
            if (!response.transactionSuccess()) { db.rollback(); player.sendMessage(msg("Payment failed: " + response.errorMessage)); return; }
            boolean regionChanged = false;
            try {
                region.getOwners().addPlayer(player.getUniqueId());
                regionChanged = true;
                try (PreparedStatement update = db.prepareStatement("UPDATE plots SET owner_uuid=?,owner_name=? WHERE id=? AND owner_uuid IS NULL")) {
                    update.setString(1, player.getUniqueId().toString()); update.setString(2, player.getName()); update.setString(3, plot.id());
                    if (update.executeUpdate() != 1) throw new SQLException("Plot ownership changed during purchase.");
                }
                try (PreparedStatement tx = db.prepareStatement("INSERT INTO transactions(plot_id,player_uuid,player_name,amount,kind) VALUES(?,?,?,?,?)")) {
                    tx.setString(1, plot.id()); tx.setString(2, player.getUniqueId().toString()); tx.setString(3, player.getName()); tx.setDouble(4, plot.price()); tx.setString(5, "PURCHASE"); tx.executeUpdate();
                }
                try (PreparedStatement entry = db.prepareStatement("INSERT INTO tournament_entries(tournament_id,plot_id,owner_uuid,owner_name) VALUES(?,?,?,?)")) {
                    entry.setString(1, tournamentId()); entry.setString(2, plot.id()); entry.setString(3, player.getUniqueId().toString()); entry.setString(4, player.getName()); entry.executeUpdate();
                }
                manager.saveChanges();
                db.commit();
                mapRenderer.invalidate();
                refreshPlotBoard(plot.id());
                player.sendMessage(msg("You purchased plot &e" + plot.id() + "&a for &e" + economy.format(plot.price()) + "&a."));
                player.sendMessage(msg("Use &e/plot home " + plot.id() + "&a to visit your plot."));
            } catch (Exception ex) {
                db.rollback();
                if (regionChanged) {
                    region.getOwners().removePlayer(player.getUniqueId());
                    try { manager.saveChanges(); } catch (Exception ignored) { }
                }
                var refund = economy.depositPlayer(player, plot.price());
                if (refund.transactionSuccess()) {
                    player.sendMessage(msg("Purchase failed. Your payment was refunded."));
                } else {
                    player.sendMessage(msg("Purchase failed and the automatic refund also failed. Contact an administrator immediately."));
                    getLogger().severe("CRITICAL: refund failed for " + player.getName() + " after plot " + plot.id() + " purchase rollback: " + refund.errorMessage);
                }
                getLogger().warning("Plot purchase rolled back: " + ex.getMessage());
            }
        } catch (Exception ex) {
            try { db.rollback(); } catch (SQLException ignored) {}
            player.sendMessage(msg("Database error while purchasing plot."));
            getLogger().warning("Purchase error: " + ex.getMessage());
        } finally {
            try { db.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    private void abandonPlot(Player player, String id) {
        if (phase() != Phase.SETUP && phase() != Phase.REGISTRATION) {
            player.sendMessage(msg("Plots can only be released during setup or registration, before construction starts."));
            return;
        }
        try {
            Plot plot = getPlot(id);
            if (plot == null || plot.ownerUuid() == null || !plot.ownerUuid().equals(player.getUniqueId().toString())) {
                player.sendMessage(msg("You do not own that plot.")); return;
            }
            RegionManager manager = regionManager(cityWorld);
            ProtectedRegion region = manager == null ? null : manager.getRegion(plotRegionId(id));
            if (region == null) { player.sendMessage(msg("Plot region is missing. Contact an admin.")); return; }
            boolean ownerRemoved = region.getOwners().contains(player.getUniqueId());
            if (ownerRemoved) region.getOwners().removePlayer(player.getUniqueId());
            try {
                db.setAutoCommit(false);
                try (PreparedStatement ps = db.prepareStatement("UPDATE plots SET owner_uuid=NULL,owner_name=NULL WHERE id=? AND owner_uuid=?")) {
                    ps.setString(1, plot.id()); ps.setString(2, player.getUniqueId().toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("Ownership update failed");
                }
                if (phase() == Phase.REGISTRATION) {
                    try (PreparedStatement entry = db.prepareStatement("DELETE FROM tournament_entries WHERE tournament_id=? AND plot_id=?")) {
                        entry.setString(1, tournamentId()); entry.setString(2, plot.id()); entry.executeUpdate();
                    }
                }
                manager.saveChanges();
                db.commit();
            } catch (Exception ex) {
                try { db.rollback(); } catch (SQLException ignored) { }
                if (ownerRemoved) region.getOwners().addPlayer(player.getUniqueId());
                try { manager.saveChanges(); } catch (Exception saveEx) { getLogger().severe("Could not restore WorldGuard owner for " + id + ": " + saveEx.getMessage()); }
                throw ex;
            } finally {
                try { db.setAutoCommit(true); } catch (SQLException ignored) { }
            }
            if (getConfig().getBoolean("plots.refund-on-abandon", false)) {
                var refund = economy.depositPlayer(player, plot.price());
                if (!refund.transactionSuccess()) getLogger().severe("Refund failed for " + player.getName() + " plot " + id + ": " + refund.errorMessage);
            }
            mapRenderer.invalidate();
            refreshPlotBoard(id);
            player.sendMessage(msg("You released plot " + id + "."));
        } catch (Exception ex) { player.sendMessage(msg("Could not release plot: " + ex.getMessage())); getLogger().warning("Abandon failed for " + id + ": " + ex.getMessage()); }
    }

    private void teleportCitySpawn(Player player) {
        if (cityWorld == null) { player.sendMessage(msg("City world is not loaded.")); return; }
        player.teleport(cityWorld.getSpawnLocation());
        player.sendMessage(msg("Teleported to ClasherCity spawn."));
    }

    private void enterPlot(Player player, String id) {
        if (phase() != Phase.REGISTRATION) { player.sendMessage(msg("Plot entries are open only during registration.")); return; }
        try {
            Plot plot = getPlot(id);
            if (plot == null || plot.ownerUuid() == null || !plot.ownerUuid().equals(player.getUniqueId().toString())) {
                player.sendMessage(msg("You must own this plot to enter it in the tournament.")); return;
            }
            if (isTournamentEntry(plot.id())) { player.sendMessage(msg("This plot is already entered in the current tournament.")); return; }
            int maxEntries = getConfig().getInt("plots.max-per-player", 2);
            if (!getConfig().getBoolean("plots.allow-multiple", true)) maxEntries = 1;
            try (PreparedStatement count = db.prepareStatement("SELECT COUNT(*) FROM tournament_entries WHERE tournament_id=? AND owner_uuid=?")) {
                count.setString(1, tournamentId()); count.setString(2, player.getUniqueId().toString());
                try (ResultSet rs = count.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= maxEntries) { player.sendMessage(msg("You have reached the tournament entry limit of " + maxEntries + ".")); return; }
                }
            }
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO tournament_entries(tournament_id,plot_id,owner_uuid,owner_name) VALUES(?,?,?,?)")) {
                insert.setString(1, tournamentId()); insert.setString(2, plot.id()); insert.setString(3, player.getUniqueId().toString()); insert.setString(4, player.getName());
                insert.executeUpdate();
            }
            player.sendMessage(msg("Plot &e" + plot.id() + "&a entered in &e" + tournamentName() + "&a."));
        } catch (SQLException ex) { player.sendMessage(msg("Could not enter plot: " + ex.getMessage())); }
    }

    private void showPlotInfo(Player player, String id) {
        try {
            Plot p = getPlot(id);
            if (p == null) { player.sendMessage(msg("Plot not found.")); return; }
            String owner = p.ownerName() == null ? "Available" : p.ownerName();
            player.sendMessage(color("&6Plot &e" + p.id() + " &7| &6Size: &f" + (p.maxX() - p.minX() + 1) + "x" + (p.maxZ() - p.minZ() + 1)
                    + " &7| &6Price: &e" + economy.format(p.price()) + " &7| &6Owner: &f" + owner));
        } catch (SQLException ex) { player.sendMessage(msg("Database error.")); }
    }

    private void submitPlotScreenshot(Player player, String[] args) {
        try {
            Plot plot=getPlot(args[1]);
            if(plot==null || plot.ownerUuid()==null || (!plot.ownerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission("clashercity.admin"))) {
                player.sendMessage(msg("Only the plot owner or an admin can add gallery screenshots.")); return;
            }
            java.net.URI uri=java.net.URI.create(args[2]);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || args[2].length()>1000) {
                player.sendMessage(msg("Screenshot URL must be a valid HTTPS image link.")); return;
            }
            String caption=args.length>3?String.join(" ",Arrays.copyOfRange(args,3,args.length)):"";
            if(caption.length()>120) caption=caption.substring(0,120);
            try(PreparedStatement ps=db.prepareStatement("INSERT INTO plot_gallery(plot_id,owner_uuid,owner_name,image_url,caption) VALUES(?,?,?,?,?)")){
                ps.setString(1,plot.id()); ps.setString(2,player.getUniqueId().toString()); ps.setString(3,player.getName()); ps.setString(4,uri.toString()); ps.setString(5,caption); ps.executeUpdate();
            }
            player.sendMessage(msg("Screenshot link added to gallery for plot &e"+plot.id()+"&a."));
        } catch(IllegalArgumentException ex){ player.sendMessage(msg("Invalid screenshot URL. Use a direct HTTPS image link.")); }
        catch(SQLException ex){ player.sendMessage(msg("Could not save screenshot to gallery.")); }
    }

    private void showPlotGallery(Player player, String plotId) {
        try {
            Plot plot=getPlot(plotId);
            if(plot==null){ player.sendMessage(msg("Plot not found.")); return; }
            player.sendMessage(color("&6---- Gallery: "+plot.id()+" ----"));
            try(PreparedStatement ps=db.prepareStatement("SELECT owner_name,image_url,caption,created_at FROM plot_gallery WHERE plot_id=? ORDER BY id DESC LIMIT 10")){
                ps.setString(1,plot.id());
                try(ResultSet rs=ps.executeQuery()){
                    int count=0;
                    while(rs.next()){
                        count++;
                        player.sendMessage(color("&e"+count+". &f"+rs.getString("caption")+" &7by "+rs.getString("owner_name")+" &8("+rs.getString("created_at")+")"));
                        player.sendMessage(color("&b"+rs.getString("image_url")));
                    }
                    if(count==0) player.sendMessage(msg("No screenshots submitted yet."));
                }
            }
        } catch(SQLException ex){ player.sendMessage(msg("Could not load plot gallery.")); }
    }

    private void updateConstructionProgress(Player player, String[] args) {
        try {
            Plot plot = getPlot(args[1]);
            if (plot == null || plot.ownerUuid() == null) { player.sendMessage(msg("Plot not found or unowned.")); return; }
            if (!player.hasPermission("clashercity.admin") && !plot.ownerUuid().equals(player.getUniqueId().toString())) {
                player.sendMessage(msg("Only the plot owner or an admin can update construction progress.")); return;
            }
            String milestone = args[2].toUpperCase(Locale.ROOT);
            Set<String> allowed = Set.of("FOUNDATION", "STRUCTURE", "EXTERIOR", "INTERIOR", "DETAILING", "COMPLETED");
            if (!allowed.contains(milestone)) { player.sendMessage(msg("Invalid milestone. Use foundation, structure, exterior, interior, detailing or completed.")); return; }
            int percent = Integer.parseInt(args[3]);
            if (percent < 0 || percent > 100) { player.sendMessage(msg("Progress must be between 0 and 100.")); return; }
            if (milestone.equals("COMPLETED")) percent = 100;
            String deadline = null;
            if (args.length >= 5) deadline = java.time.LocalDate.parse(args[4]).toString();
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO construction_progress(plot_id,milestone,percent,deadline,updated_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP) ON CONFLICT(plot_id) DO UPDATE SET milestone=excluded.milestone,percent=excluded.percent,deadline=COALESCE(excluded.deadline,construction_progress.deadline),updated_at=CURRENT_TIMESTAMP")) {
                ps.setString(1, plot.id()); ps.setString(2, milestone); ps.setInt(3, percent); ps.setString(4, deadline); ps.executeUpdate();
            }
            player.sendMessage(msg("Construction progress for &e" + plot.id() + "&a updated: &f" + milestone + " &7• &e" + percent + "%."));
        } catch (NumberFormatException ex) {
            player.sendMessage(msg("Progress must be a number from 0 to 100."));
        } catch (java.time.format.DateTimeParseException ex) {
            player.sendMessage(msg("Deadline must use YYYY-MM-DD."));
        } catch (SQLException ex) { player.sendMessage(msg("Could not update construction progress.")); }
    }

    private String constructionProgressLine(String plotId) {
        try (PreparedStatement ps = db.prepareStatement("SELECT milestone,percent,deadline FROM construction_progress WHERE plot_id=?")) {
            ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "&7Construction: &fNot reported";
                String deadline = rs.getString("deadline");
                return "&7Construction: &e" + rs.getString("milestone") + " &7• &f" + rs.getInt("percent") + "%"
                        + (deadline == null ? "" : " &7• Due &f" + deadline);
            }
        } catch (SQLException ex) { return "&7Construction: &cUnavailable"; }
    }

    private boolean plotVisitsEnabled(UUID uuid) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT visits_enabled FROM plot_visit_settings WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return !rs.next() || rs.getInt(1) == 1; }
        }
    }

    private void setPlotVisits(Player player, boolean enabled) {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO plot_visit_settings(player_uuid,visits_enabled) VALUES(?,?) ON CONFLICT(player_uuid) DO UPDATE SET visits_enabled=excluded.visits_enabled")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setInt(2, enabled ? 1 : 0);
            ps.executeUpdate();
            player.sendMessage(msg("Plot visits are now " + (enabled ? "&aenabled" : "&cdisabled") + "&a."));
        } catch (SQLException ex) { player.sendMessage(msg("Could not update visit settings.")); }
    }

    private void visitPlayerPlot(Player visitor, String targetName) {
        if (targetName == null || targetName.isBlank()) { visitor.sendMessage(msg("Usage: /plot visit <player>")); return; }
        try {
            Plot plot = null;
            try (PreparedStatement ps = db.prepareStatement("SELECT * FROM plots WHERE lower(owner_name)=lower(?) ORDER BY id LIMIT 1")) {
                ps.setString(1, targetName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) plot = new Plot(rs.getString("id"), rs.getString("world"), rs.getInt("min_x"), rs.getInt("min_z"),
                            rs.getInt("max_x"), rs.getInt("max_z"), rs.getDouble("price"), rs.getString("owner_uuid"), rs.getString("owner_name"));
                }
            }
            if (plot == null || plot.ownerUuid() == null) { visitor.sendMessage(msg("That player does not own a ClasherCity plot.")); return; }
            UUID ownerId = UUID.fromString(plot.ownerUuid());
            if (ownerId.equals(visitor.getUniqueId())) { teleportHome(visitor, plot.id()); return; }
            if (!plotVisitsEnabled(ownerId)) { visitor.sendMessage(msg("That player has disabled plot visits.")); return; }
            World world = Bukkit.getWorld(plot.world());
            if (world == null) { visitor.sendMessage(msg("The city world is not loaded.")); return; }
            int x = (plot.minX() + plot.maxX()) / 2, z = (plot.minZ() + plot.maxZ()) / 2;
            visitor.teleport(new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1.0, z + 0.5));
            visitor.sendMessage(msg("Visiting &e" + plot.ownerName() + "&a's plot &f" + plot.id() + "&a."));
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null && getConfig().getBoolean("plots.notify-owner-on-visit", true))
                owner.sendMessage(msg("&e" + visitor.getName() + "&a is visiting your plot &f" + plot.id() + "&a."));
        } catch (SQLException ex) { visitor.sendMessage(msg("Could not load that player's plot.")); }
        catch (IllegalArgumentException ex) { visitor.sendMessage(msg("The plot owner record is invalid. Contact staff.")); }
    }

    private void teleportHome(Player player, String id) {
        try {
            Plot plot = null;
            if (id != null) {
                plot = getPlot(id);
                if (plot == null || plot.ownerUuid() == null || !plot.ownerUuid().equals(player.getUniqueId().toString())) {
                    player.sendMessage(msg("You do not own that plot.")); return;
                }
            } else {
                try (PreparedStatement ps = db.prepareStatement("SELECT * FROM plots WHERE owner_uuid=? ORDER BY id LIMIT 1")) {
                    ps.setString(1, player.getUniqueId().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) plot = new Plot(rs.getString("id"), rs.getString("world"), rs.getInt("min_x"), rs.getInt("min_z"), rs.getInt("max_x"),
                                rs.getInt("max_z"), rs.getDouble("price"), rs.getString("owner_uuid"), rs.getString("owner_name"));
                    }
                }
            }
            if (plot == null) { player.sendMessage(msg("You do not own a city plot.")); return; }
            World world = Bukkit.getWorld(plot.world());
            if (world == null) { player.sendMessage(msg("City world is not loaded.")); return; }
            int x = (plot.minX() + plot.maxX()) / 2, z = (plot.minZ() + plot.maxZ()) / 2;
            Location target = new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1.0, z + 0.5);
            player.teleport(target);
        } catch (SQLException ex) { player.sendMessage(msg("Database error.")); }
    }

    // ------------------------------- Menus ---------------------------------

    private class MenuHolder implements InventoryHolder {
        private final String type;
        private final int page;
        private Inventory inventory;
        MenuHolder(String type, int page) { this.type = type; this.page = page; }
        Inventory create(int size, String title) { inventory = Bukkit.createInventory(this, size, title); return inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private ItemStack menuItem(Material material, String name, List<String> lore, String action, String plotId) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(this::color).toList());
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS, org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
        if (action != null) meta.getPersistentDataContainer().set(keyAction, PersistentDataType.STRING, action);
        if (plotId != null) meta.getPersistentDataContainer().set(keyPlot, PersistentDataType.STRING, plotId);
        item.setItemMeta(meta);
        return item;
    }

    private void frameMenu(Inventory inv, Material material) {
        Material accent = material == Material.GRAY_STAINED_GLASS_PANE ? Material.LIGHT_BLUE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
        ItemStack pane = menuItem(material, "&8 ", List.of(), "none", null);
        ItemStack accentPane = menuItem(accent, "&8 ", List.of(), "none", null);
        int rows = inv.getSize() / 9;
        for (int col = 0; col < 9; col++) { inv.setItem(col, col == 4 ? accentPane : pane); inv.setItem((rows - 1) * 9 + col, col == 4 ? accentPane : pane); }
        for (int row = 1; row < rows - 1; row++) { inv.setItem(row * 9, accentPane); inv.setItem(row * 9 + 8, accentPane); }
    }

    private void openMainMenu(Player player) {
        MenuHolder holder = new MenuHolder("MAIN", 0);
        Inventory inv = holder.create(27, color("&8✦  CLASHERCITY  &8•  &7CITY PORTAL"));
        frameMenu(inv, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(11, menuItem(Material.COMPASS, "&b&lCITY SPAWN", List.of("&8DESTINATION", "", "&7Teleport to the city hub.", "&7Explore the ClasherCity district.", "", "&e➜ Click to travel"), "visit", null));
        inv.setItem(13, menuItem(Material.LIME_CONCRETE, "&a&lPLOT MARKETPLACE", List.of("&8REAL ESTATE", "", "&7Browse plots and their prices.", "&7Purchase your own city property.", "", "&e➜ Browse properties"), "plots", null));
        inv.setItem(15, menuItem(Material.FILLED_MAP, "&3&lCITY ATLAS", List.of("&8CITY OVERVIEW", "", "&7View the city layout.", "&7Check available and sold plots.", "", "&e➜ Open atlas"), "map", null));
        inv.setItem(17, menuItem(Material.RECOVERY_COMPASS, "&d&lCITY DESTINATIONS", List.of("&8TELEPORT DIRECTORY", "", "&7Browse registered districts,", "&7parks and public landmarks.", "", "&e➜ Choose destination"), "destinations", null));
        inv.setItem(20, menuItem(Material.OAK_DOOR, "&e&lMY PROPERTY", List.of("&8PERSONAL SHORTCUT", "", "&7Teleport to your owned plot.", "", "&e➜ Go to my plot"), "home", null));
        inv.setItem(22, menuItem(Material.WRITABLE_BOOK, "&d&lCHAMPIONSHIP", List.of("&8BUILDING TOURNAMENT", "", "&7Current phase: &f" + phase(), "&7Open tournament voting.", "", "&e➜ View tournament"), "vote", null));
        inv.setItem(24, menuItem(Material.GOLD_INGOT, "&6&lHALL OF FAME", List.of("&8LEADERBOARD", "", "&7See the current top builders.", "&7Check tournament standings.", "", "&e➜ View rankings"), "top", null));
        player.openInventory(inv);
    }

    private void openDestinationsMenu(Player player) {
        MenuHolder holder=new MenuHolder("DESTINATIONS",0);
        Inventory inv=holder.create(54,color("&8✦  CITY DESTINATIONS  &8•  &7TRAVEL"));
        frameMenu(inv,Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4,menuItem(Material.RECOVERY_COMPASS,"&d&lCITY DIRECTORY",List.of("&7Registered teleport points","&7and public city spaces."),"none",null));
        inv.setItem(10,menuItem(Material.COMPASS,"&b&lCITY SPAWN",List.of("&7Return to the main city hub.","","&e➜ Teleport"),"destination","spawn"));
        int[] slots={11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        int index=0;
        try {
            for(Area area:getAreas()) if(area.type().equalsIgnoreCase("PUBLIC") && index<slots.length) {
                inv.setItem(slots[index++],menuItem(Material.OAK_LEAVES,"&a&l"+area.id(),List.of("&8PUBLIC AREA / PARK","&7Teleport to the centre","&7of this registered public area.","","&e➜ Visit location"),"destination","area:"+area.id()));
            }
        } catch(SQLException ex){ player.sendMessage(msg("Could not load public destinations.")); }
        if(getConfig().isConfigurationSection("destinations")) {
            var section=getConfig().getConfigurationSection("destinations");
            for(String id:section.getKeys(false)) if(index<slots.length) {
                inv.setItem(slots[index++],menuItem(Material.LODESTONE,"&d&l"+id,List.of("&8CITY LANDMARK","&7Registered destination.","","&e➜ Teleport"),"destination","loc:"+id));
            }
        }
        inv.setItem(49,menuItem(Material.BARRIER,"&cClose",List.of("&7Close directory."),"close",null));
        player.openInventory(inv);
    }

    private void teleportDestination(Player player,String target) {
        try {
            if(target.equalsIgnoreCase("spawn")){ player.closeInventory(); teleportCitySpawn(player); return; }
            Location destination=null;
            if(target.startsWith("area:")){
                String id=target.substring(5);
                Area area=getAreas().stream().filter(a->a.id().equalsIgnoreCase(id)&&a.type().equalsIgnoreCase("PUBLIC")).findFirst().orElse(null);
                if(area!=null){ int x=(area.minX()+area.maxX())/2, z=(area.minZ()+area.maxZ())/2; destination=new Location(cityWorld,x+0.5,cityWorld.getHighestBlockYAt(x,z)+1,z+0.5); }
            } else if(target.startsWith("loc:")){
                String id=target.substring(4), path="destinations."+id;
                World w=Bukkit.getWorld(getConfig().getString(path+".world",""));
                if(w!=null) destination=new Location(w,getConfig().getDouble(path+".x"),getConfig().getDouble(path+".y"),getConfig().getDouble(path+".z"),(float)getConfig().getDouble(path+".yaw"),(float)getConfig().getDouble(path+".pitch"));
            }
            if(destination==null){ player.sendMessage(msg("Destination is unavailable or was removed.")); return; }
            player.closeInventory(); player.teleport(destination); player.sendMessage(msg("Teleported to &e"+target.replace("area:","").replace("loc:","")+"&a."));
        } catch(SQLException ex){ player.sendMessage(msg("Could not load destination.")); }
    }

    private void openAdminMenu(Player player) {
        MenuHolder holder = new MenuHolder("ADMIN", 0);
        Inventory inv = holder.create(36, color("&8✦  CLASHERCITY  &8•  &cADMIN CONSOLE"));
        frameMenu(inv, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, menuItem(Material.NETHER_STAR, "&b&lCITY OPERATIONS", List.of("&8ADMINISTRATION", "", "&7World: &f" + (cityWorld == null ? "Not loaded" : cityWorld.getName()), "&7Tournament: &f" + tournamentName(), "&7Phase: &e" + phase(), "", "&8Select an operation below."), "none", null));
        inv.setItem(10, menuItem(Material.GRASS_BLOCK, "&a&lWORLD MANAGEMENT", List.of("&7Load or create the city world.", "", "&e➜ Click to execute &8• &f/clcity create"), "admin_create", null));
        inv.setItem(12, menuItem(Material.BLAZE_ROD, "&b&lSELECTION TOOL", List.of("&7Receive the region selection wand.", "", "&e➜ Click to receive"), "admin_wand", null));
        inv.setItem(14, menuItem(Material.FILLED_MAP, "&3&lMAP MANAGEMENT", List.of("&7Give yourself the physical city map.", "", "&e➜ Click to receive"), "admin_map", null));
        inv.setItem(16, menuItem(Material.GOLD_INGOT, "&6&lTOURNAMENT SETUP", List.of("&7Open plot registration for a new event.", "&7Current phase: &f" + phase(), "", "&e➜ Start championship"), "admin_start", null));
        inv.setItem(20, menuItem(Material.BRICKS, "&e&lBUILDING PHASE", List.of("&7Allow plot owners to build.", "", "&e➜ Set phase to BUILDING"), "admin_building", null));
        inv.setItem(22, menuItem(Material.WRITABLE_BOOK, "&d&lVOTING CONTROL", List.of("&7Open player voting for registered plots.", "", "&e➜ Set phase to VOTING"), "admin_voting", null));
        inv.setItem(24, menuItem(Material.GOLD_BLOCK, "&6&lFINAL LEADERBOARD", List.of("&7Review current tournament scores.", "", "&e➜ View results"), "admin_results", null));
        inv.setItem(31, menuItem(Material.BARRIER, "&cClose console", List.of("&7Return to the game."), "close", null));
        player.openInventory(inv);
    }

    private void openPlotMenu(Player player, int page) {
        List<Plot> plots;
        try { plots = getPlots(); } catch (SQLException ex) { player.sendMessage(msg("Could not load plots.")); return; }
        int perPage = 28;
        int maxPage = Math.max(0, (plots.size() - 1) / perPage);
        page = Math.max(0, Math.min(page, maxPage));
        MenuHolder holder = new MenuHolder("PLOTS", page);
        Inventory inv = holder.create(54, color("&8✦  CITY MARKETPLACE  &8•  &7" + (page + 1) + "/" + (maxPage + 1)));
        frameMenu(inv, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(4, menuItem(Material.EMERALD, "&a&lPROPERTY MARKETPLACE", List.of("&8CLASHERCITY REAL ESTATE", "", "&7Registered properties: &f" + plots.size(), "&7Available: &a" + plots.stream().filter(p -> p.ownerUuid() == null).count(), "&7Purchased: &c" + plots.stream().filter(p -> p.ownerUuid() != null).count(), "", "&8Select a property to inspect details."), "none", null));
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        int start = page * perPage;
        for (int i = 0; i < slots.length && start + i < plots.size(); i++) {
            Plot p = plots.get(start + i);
            boolean sold = p.ownerUuid() != null;
            inv.setItem(slots[i], menuItem(sold ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                    (sold ? "&c&l" : "&a&l") + p.id(),
                    List.of("&8PROPERTY LISTING", "", "&7Dimensions: &f" + (p.maxX()-p.minX()+1) + " x " + (p.maxZ()-p.minZ()+1),
                            "&7Price: &e" + economy.format(p.price()),
                            sold ? "&7Status: &cPurchased" : "&7Status: &aAvailable",
                            sold ? "&7Owner: &f" + p.ownerName() : "&7Owner: &fUnclaimed", "",
                            sold ? "&e➜ View property details" : "&a➜ View / purchase options"), "plot_info", p.id()));
        }
        if (page > 0) inv.setItem(48, menuItem(Material.ARROW, "&fPrevious page", List.of("&7Page " + page), "prev", null));
        inv.setItem(49, menuItem(Material.FILLED_MAP, "&bCity atlas", List.of("&7Browse the city overview."), "map", null));
        inv.setItem(50, menuItem(Material.BARRIER, "&cClose", List.of("&7Close marketplace."), "close", null));
        if (page < maxPage) inv.setItem(52, menuItem(Material.ARROW, "&fNext page", List.of("&7Page " + (page + 2)), "next", null));
        player.openInventory(inv);
    }

    private void openPlotInfoMenu(Player player, String id) {
        try {
            Plot p = getPlot(id);
            if (p == null) { player.sendMessage(msg("Plot not found.")); return; }
            MenuHolder holder = new MenuHolder("PLOT_INFO", 0);
            Inventory inv = holder.create(36, color("&8✦  PROPERTY PROFILE  &8•  &f" + p.id()));
            frameMenu(inv, Material.BLACK_STAINED_GLASS_PANE);
            boolean sold = p.ownerUuid() != null;
            int sizeX = p.maxX() - p.minX() + 1;
            int sizeZ = p.maxZ() - p.minZ() + 1;
            inv.setItem(4, menuItem(sold ? Material.RED_CONCRETE : Material.LIME_CONCRETE,
                    (sold ? "&c&l" : "&a&l") + "PLOT " + p.id(),
                    List.of("&8CLASHERCITY PROPERTY RECORD", "",
                            "&7Availability: " + (sold ? "&cPurchased" : "&aAvailable"),
                            "&7Owner: &f" + (sold ? p.ownerName() : "Unclaimed"),
                            "&7Market price: &e" + economy.format(p.price()),
                            "&7Building phase: &f" + phase(),
                            "", sold ? "&cThis property is not available for purchase." : "&aThis property is available."),
                    "info", p.id()));
            inv.setItem(11, menuItem(Material.MAP, "&b&lPROPERTY DIMENSIONS",
                    List.of("&8LAND INFORMATION", "",
                            "&7Footprint: &f" + sizeX + " x " + sizeZ + " blocks",
                            "&7Total area: &f" + ((long) sizeX * sizeZ) + " blocks²",
                            "&7World: &f" + p.world(),
                            constructionProgressLine(p.id()),
                            "&7Minimum corner: &f" + p.minX() + ", " + p.minZ(),
                            "&7Maximum corner: &f" + p.maxX() + ", " + p.maxZ()),
                    "info", p.id()));
            inv.setItem(13, menuItem(Material.GOLD_INGOT, "&6&lPURCHASE INFORMATION",
                    List.of("&8REAL ESTATE", "",
                            "&7Listed price: &e" + economy.format(p.price()),
                            "&7Status: " + (sold ? "&cSold" : "&aFor sale"),
                            "&7Transaction: &fVault economy",
                            "", sold ? "&cOwned by " + p.ownerName() : "&ePurchase is available during registration."),
                    "info", p.id()));
            if (!sold && phase() == Phase.REGISTRATION) {
                inv.setItem(22, menuItem(Material.LIME_CONCRETE, "&a&lPURCHASE PROPERTY",
                        List.of("&7Acquire plot " + p.id() + ".", "&7Price: &e" + economy.format(p.price()),
                                "", "&e➜ Continue to purchase confirmation"), "confirm", p.id()));
            } else if (sold && p.ownerUuid().equals(player.getUniqueId().toString()) && phase() == Phase.REGISTRATION) {
                boolean entered = isTournamentEntry(p.id());
                inv.setItem(22, menuItem(entered ? Material.LIME_DYE : Material.WRITABLE_BOOK,
                        entered ? "&a&lCHAMPIONSHIP REGISTERED" : "&e&lENTER CHAMPIONSHIP",
                        List.of("&7Register your property in the current build event.",
                                "", entered ? "&aThis plot is already entered." : "&e➜ Register this plot"),
                        entered ? "none" : "enter", p.id()));
            } else {
                inv.setItem(22, menuItem(Material.BARRIER, "&c&lNO ACTION AVAILABLE",
                        List.of("&7Purchases and registration depend on", "&7the current tournament phase: &f" + phase()),
                        "info", p.id()));
            }
            inv.setItem(30, menuItem(Material.ARROW, "&fBack to marketplace", List.of("&7Return to property listings."), "plots", null));
            inv.setItem(32, menuItem(Material.BARRIER, "&cClose", List.of("&7Close property details."), "close", null));
            player.openInventory(inv);
        } catch (SQLException ex) { player.sendMessage(msg("Database error.")); }
    }

    private void openMapMenu(Player player, int page) {
        List<Plot> plots;
        try { plots = getPlots(); } catch (SQLException ex) { player.sendMessage(msg("Could not load map.")); return; }
        int perPage = 28;
        int maxPage = Math.max(0, (plots.size() - 1) / perPage);
        page = Math.max(0, Math.min(page, maxPage));
        MenuHolder holder = new MenuHolder("MAP", page);
        Inventory inv = holder.create(54, color("&8✦  CLASHERCITY  &8•  &3CITY ATLAS"));
        frameMenu(inv, Material.BLACK_STAINED_GLASS_PANE);
        long available = plots.stream().filter(p -> p.ownerUuid() == null).count();
        long sold = plots.size() - available;
        inv.setItem(4, menuItem(Material.FILLED_MAP, "&b&lCITY ATLAS",
                List.of("&8DISTRICT PROPERTY INDEX", "",
                        "&7Registered plots: &f" + plots.size(),
                        "&7Available properties: &a" + available,
                        "&7Purchased properties: &c" + sold,
                        "", "&8Colour key", "&a■ &7Available   &c■ &7Purchased",
                        "&8Select a plot to open its property profile."),
                "none", null));
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        int start = page * perPage;
        for (int i = 0; i < slots.length && start + i < plots.size(); i++) {
            Plot p = plots.get(start + i);
            boolean isSold = p.ownerUuid() != null;
            inv.setItem(slots[i], menuItem(isSold ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                    (isSold ? "&c" : "&a") + "&l" + p.id(),
                    List.of("&8ATLAS PROPERTY MARKER", "",
                            "&7Status: " + (isSold ? "&cPurchased" : "&aAvailable"),
                            "&7Price: &e" + economy.format(p.price()),
                            isSold ? "&7Owner: &f" + p.ownerName() : "&7Owner: &fUnclaimed",
                            "", "&e➜ Open property profile"),
                    "plot_info", p.id()));
        }
        if (plots.isEmpty()) inv.setItem(22, menuItem(Material.BARRIER, "&cNo registered plots", List.of("&7No property markers are available yet."), "none", null));
        if (page > 0) inv.setItem(48, menuItem(Material.ARROW, "&fPrevious page", List.of("&7Page " + page), "map_prev", null));
        inv.setItem(49, menuItem(Material.PAPER, "&ePhysical map item", List.of("&7Admins can issue the in-game map", "&7with /clcity mapitem."), "none", null));
        inv.setItem(50, menuItem(Material.BARRIER, "&cClose", List.of("&7Close the city atlas."), "close", null));
        if (page < maxPage) inv.setItem(52, menuItem(Material.ARROW, "&fNext page", List.of("&7Page " + (page + 2)), "map_next", null));
        player.openInventory(inv);
    }

    private void openVoteMenu(Player player) { openVoteMenu(player, 0); }

    private void openVoteMenu(Player player, int requestedPage) {
        if (phase() != Phase.VOTING) { player.sendMessage(msg("Voting is not open right now.")); return; }
        if (!isEligibleVoter(player)) { player.sendMessage(msg("You do not meet the minimum playtime requirement.")); return; }
        List<Plot> plots;
        try { plots = getTournamentPlots(); }
        catch (SQLException ex) { player.sendMessage(msg("Could not load entries.")); return; }
        List<Plot> eligible = plots.stream().filter(p -> p.ownerUuid() != null && !p.ownerUuid().equals(player.getUniqueId().toString())).toList();
        int perPage = 28;
        int maxPage = Math.max(0, (eligible.size() - 1) / perPage);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        MenuHolder holder = new MenuHolder("VOTE", page);
        Inventory inv = holder.create(54, color("&8✦  CHAMPIONSHIP  &8•  &6VOTING  " + (page + 1) + "/" + (maxPage + 1)));
        frameMenu(inv, Material.BLACK_STAINED_GLASS_PANE);
        boolean alreadyVoted = hasVoted(player);
        inv.setItem(4, menuItem(alreadyVoted ? Material.LIME_DYE : Material.GOLD_INGOT,
                alreadyVoted ? "&a&lVOTE SUBMITTED" : "&6&lCOMMUNITY VOTING",
                List.of("&8" + tournamentName(), "", "&7Eligible entries: &f" + eligible.size(),
                        "&7Your status: " + (alreadyVoted ? "&aVote recorded" : "&eNot voted"),
                        "", alreadyVoted ? "&aThank you for participating." : "&7Choose one build below."), "none", null));
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        int start = page * perPage;
        for (int i = 0; i < slots.length && start + i < eligible.size(); i++) {
            Plot p = eligible.get(start + i);
            inv.setItem(slots[i], menuItem(alreadyVoted ? Material.GRAY_DYE : Material.PAPER,
                    (alreadyVoted ? "&7" : "&e&l") + p.id() + " &8• &f" + p.ownerName(),
                    List.of("&8CHAMPIONSHIP ENTRY", "", "&7Builder: &f" + p.ownerName(),
                            "&7Plot: &f" + p.id(), "",
                            alreadyVoted ? "&cYou have already voted." : "&aClick to submit your vote.",
                            "&8One vote per player."), alreadyVoted ? "none" : "vote", p.id()));
        }
        if (eligible.isEmpty()) inv.setItem(22, menuItem(Material.BARRIER, "&cNo eligible entries", List.of("&7Other players must register owned plots."), "none", null));
        if (page > 0) inv.setItem(48, menuItem(Material.ARROW, "&fPrevious page", List.of("&7Page " + page), "vote_prev", null));
        inv.setItem(49, menuItem(Material.BARRIER, "&cClose", List.of("&7Close voting menu."), "close", null));
        if (page < maxPage) inv.setItem(52, menuItem(Material.ARROW, "&fNext page", List.of("&7Page " + (page + 2)), "vote_next", null));
        player.openInventory(inv);
    }

    private boolean hasVoted(Player player) {
        try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM votes WHERE tournament_id=? AND voter_uuid=? LIMIT 1")) {
            ps.setString(1, tournamentId());
            ps.setString(2, player.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException ex) {
            getLogger().warning("Could not check voting status: " + ex.getMessage());
            return true;
        }
    }

    private void openVoteConfirm(Player player, String plotId) {
        try {
            Plot plot = getPlot(plotId);
            if (plot == null || plot.ownerUuid() == null) { player.sendMessage(msg("This entry is unavailable.")); return; }
            if (plot.ownerUuid().equals(player.getUniqueId().toString())) { player.sendMessage(msg("You cannot vote for your own plot.")); return; }
            if (hasVoted(player)) { player.sendMessage(msg("You have already voted in this tournament.")); return; }
            MenuHolder holder = new MenuHolder("VOTE_CONFIRM", 0);
            Inventory inv = holder.create(27, color("&8✦  CONFIRM CHAMPIONSHIP VOTE"));
            frameMenu(inv, Material.BLACK_STAINED_GLASS_PANE);
            inv.setItem(13, menuItem(Material.PAPER, "&6&lCONFIRM YOUR VOTE",
                    List.of("&8FINAL SELECTION", "", "&7Tournament: &f" + tournamentName(),
                            "&7Plot: &e" + plot.id(), "&7Builder: &f" + plot.ownerName(),
                            "", "&cYour vote cannot be changed after submission.",
                            "&7Please verify your selection."), "info", plotId));
            inv.setItem(11, menuItem(Material.LIME_DYE, "&a&lSUBMIT VOTE",
                    List.of("&7Submit one vote for " + plot.id() + "."), "vote_submit", plotId));
            inv.setItem(15, menuItem(Material.RED_DYE, "&c&lGO BACK",
                    List.of("&7Return to the voting list."), "vote_back", null));
            player.openInventory(inv);
        } catch (SQLException ex) { player.sendMessage(msg("Could not prepare vote confirmation.")); }
    }

    private void castVote(Player player, String plotId) {
        if (phase() != Phase.VOTING) { player.sendMessage(msg("Voting is closed.")); return; }
        if (!isEligibleVoter(player)) { player.sendMessage(msg("You do not meet the minimum playtime requirement.")); return; }
        try {
            Plot plot = getPlot(plotId);
            if (plot == null || plot.ownerUuid() == null) { player.sendMessage(msg("This entry is unavailable.")); return; }
            if (plot.ownerUuid().equals(player.getUniqueId().toString())) { player.sendMessage(msg("You cannot vote for your own plot.")); return; }
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO votes(tournament_id,voter_uuid,voter_name,plot_id) VALUES(?,?,?,?)")) {
                ps.setString(1, tournamentId()); ps.setString(2, player.getUniqueId().toString()); ps.setString(3, player.getName()); ps.setString(4, plotId); ps.executeUpdate();
                player.closeInventory(); player.sendMessage(msg("Your vote for &e" + plotId + "&a has been recorded. You cannot vote again."));
            } catch (SQLException ex) {
                String detail = Objects.toString(ex.getMessage(), "").toLowerCase(Locale.ROOT);
                if (ex.getErrorCode() == 19 || detail.contains("unique constraint")) {
                    player.sendMessage(msg("You have already voted in this tournament."));
                } else {
                    getLogger().warning("Vote insert failed: " + ex.getMessage());
                    player.sendMessage(msg("Could not save vote. Please contact staff."));
                }
            }
        } catch (SQLException ex) { player.sendMessage(msg("Could not save vote.")); }
    }

    private boolean isEligibleVoter(Player player) {
        int minimumMinutes = getConfig().getInt("voting.minimum-playtime-minutes", 0);
        if (minimumMinutes <= 0) return true;
        int ticks;
        try { ticks = player.getStatistic(Statistic.PLAY_ONE_MINUTE); }
        catch (Exception ex) { return true; }
        return ticks / 1200 >= minimumMinutes;
    }

    // ---------------------------- Tournament ------------------------------

    private void startTournament(CommandSender sender, String name) throws SQLException {
        Phase current = phase();
        if (current == Phase.REGISTRATION || current == Phase.BUILDING || current == Phase.VOTING) {
            sender.sendMessage(msg("A tournament is already active in phase " + current + ". Finish it before starting another."));
            return;
        }
        String newId = UUID.randomUUID().toString();
        setMeta("tournament_id", newId);
        setMeta("tournament_name", name);
        for (int rank = 1; rank <= 3; rank++) setMeta("winner_" + rank, "");
        setPhase(Phase.REGISTRATION);
        sender.sendMessage(msg("Tournament started: &e" + name + "&a. Plot registration is now open."));
        broadcast(msg("&6" + name + "&a has started! Use &e/city&a or &e/plots&a to purchase a plot."));
    }

    private void closeTournament(CommandSender sender) throws Exception {
        if (phase() == Phase.RESULTS) { sender.sendMessage(msg("Results have already been finalized.")); showResults(sender); return; }
        setPhase(Phase.RESULTS);
        List<ResultRow> results = calculateResults();
        broadcast(msg("Voting has closed. Final results are being announced."));
        if (results.isEmpty()) {
            sender.sendMessage(msg("No owned plots were available for final scoring."));
            return;
        }
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            ResultRow row = results.get(i);
            broadcast(color("&6#" + (i + 1) + " &e" + row.ownerName() + " &7(plot " + row.plotId() + ") &8- &f" + String.format(Locale.US, "%.2f", row.finalScore()) + " points"));
        }
        queueAndDeliverRewards(results);
        sender.sendMessage(msg("Results finalized. Use /buildtop to view the leaderboard."));
    }

    private void judgeCommand(CommandSender sender, String[] args) throws Exception {
        if (args.length < 3) { sender.sendMessage("Usage: /clcity judge <add|remove> <player>"); return; }
        String action = args[1].toLowerCase(Locale.ROOT);
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        if (action.equals("add")) {
            try (PreparedStatement ps = db.prepareStatement("INSERT OR REPLACE INTO judges(tournament_id,judge_uuid,judge_name) VALUES(?,?,?)")) {
                ps.setString(1, tournamentId()); ps.setString(2, target.getUniqueId().toString()); ps.setString(3, target.getName() == null ? args[2] : target.getName()); ps.executeUpdate();
            }
            sender.sendMessage(msg("Judge added: " + args[2]));
        } else if (action.equals("remove")) {
            try (PreparedStatement ps = db.prepareStatement("DELETE FROM judges WHERE tournament_id=? AND judge_uuid=?")) {
                ps.setString(1, tournamentId()); ps.setString(2, target.getUniqueId().toString()); ps.executeUpdate();
            }
            sender.sendMessage(msg("Judge removed: " + args[2]));
        } else sender.sendMessage("Usage: /clcity judge <add|remove> <player>");
    }

    private Map<String, Integer> scoreCategories() {
        Map<String, Integer> map = new LinkedHashMap<>();
        var section = getConfig().getConfigurationSection("judging.categories");
        if (section != null) {
            for (String key : section.getKeys(false)) map.put(key.toLowerCase(Locale.ROOT), section.getInt(key));
        }
        if (map.isEmpty()) {
            map.put("creativity", 25); map.put("exterior", 25); map.put("interior", 20); map.put("detailing", 15); map.put("theme", 15);
        }
        return map;
    }

    private void winnerOverrideCommand(CommandSender sender, String[] args) throws Exception {
        if (args.length < 3) { sender.sendMessage("Usage: /clcity winner <1|2|3> <plot-id|auto>"); return; }
        int rank = Integer.parseInt(args[1]);
        if (rank < 1 || rank > 3) throw new IllegalArgumentException("Rank must be 1, 2 or 3.");
        String plotId = args[2];
        if (!plotId.equalsIgnoreCase("auto")) {
            Plot plot = getPlot(plotId);
            if (plot == null || plot.ownerUuid() == null || !isTournamentEntry(plot.id())) throw new IllegalArgumentException("Choose an owned plot entered in the current tournament.");
            for (int otherRank = 1; otherRank <= 3; otherRank++) {
                if (otherRank == rank) continue;
                String existing = getMeta("winner_" + otherRank);
                if (existing != null && existing.equalsIgnoreCase(plot.id())) {
                    throw new IllegalArgumentException("That plot is already manually assigned to rank " + otherRank + ". Clear that override first.");
                }
            }
            setMeta("winner_" + rank, plot.id());
            sender.sendMessage(msg("Manual winner override set: rank " + rank + " = " + plot.id() + "."));
        } else {
            setMeta("winner_" + rank, "");
            sender.sendMessage(msg("Rank " + rank + " returned to automatic scoring."));
        }
    }

    private void scoreCommand(CommandSender sender, String[] args) throws Exception {
        if (args.length < 4) { sender.sendMessage("Usage: /clcity score <plot> <category> <marks>"); return; }
        if (phase() != Phase.VOTING) { sender.sendMessage(msg("Judges can submit or revise scores only during the voting phase.")); return; }
        UUID judgeId;
        String judgeName;
        if (sender instanceof Player player) {
            judgeId = player.getUniqueId(); judgeName = player.getName();
            if (!player.hasPermission("clashercity.admin") && !isJudge(judgeId)) { player.sendMessage(msg("You are not registered as a judge.")); return; }
        } else { sender.sendMessage("Only an online judge or admin player can submit scores."); return; }
        Plot plot = getPlot(args[1]);
        if (plot == null || plot.ownerUuid() == null || !isTournamentEntry(plot.id())) { sender.sendMessage(msg("Owned tournament entry not found.")); return; }
        String category = args[2].toLowerCase(Locale.ROOT);
        Integer max = scoreCategories().get(category);
        if (max == null) { sender.sendMessage(msg("Categories: creativity, exterior, interior, detailing, theme")); return; }
        int score = Integer.parseInt(args[3]);
        if (score < 0 || score > max) { sender.sendMessage(msg("Maximum marks for " + category + " is " + max + ".")); return; }
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO scores(tournament_id,judge_uuid,plot_id,category,score,updated_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP) ON CONFLICT(tournament_id,judge_uuid,plot_id,category) DO UPDATE SET score=excluded.score,updated_at=CURRENT_TIMESTAMP")) {
            ps.setString(1, tournamentId()); ps.setString(2, judgeId.toString()); ps.setString(3, plot.id()); ps.setString(4, category); ps.setInt(5, score); ps.executeUpdate();
        }
        sender.sendMessage(msg("Saved &e" + score + "/" + max + "&a for " + category + " on " + plot.id() + "."));
    }

    private boolean isJudge(UUID uuid) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM judges WHERE tournament_id=? AND judge_uuid=?")) {
            ps.setString(1, tournamentId()); ps.setString(2, uuid.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private List<ResultRow> calculateResults() throws SQLException {
        List<Plot> owned = getTournamentPlots();
        if (owned.isEmpty()) return List.of();
        Map<String, Integer> voteCounts = new HashMap<>();
        int totalVotes = 0;
        try (PreparedStatement ps = db.prepareStatement("SELECT plot_id,COUNT(*) AS n FROM votes WHERE tournament_id=? GROUP BY plot_id")) {
            ps.setString(1, tournamentId());
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { voteCounts.put(rs.getString("plot_id"), rs.getInt("n")); totalVotes += rs.getInt("n"); } }
        }
        Map<String, List<Integer>> judgeTotals = new HashMap<>();
        String sql = "SELECT plot_id,judge_uuid,SUM(score) AS total,COUNT(*) AS categories FROM scores WHERE tournament_id=? GROUP BY plot_id,judge_uuid HAVING COUNT(*)=?";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, tournamentId());
            ps.setInt(2, scoreCategories().size());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) judgeTotals.computeIfAbsent(rs.getString("plot_id"), k -> new ArrayList<>()).add(rs.getInt("total"));
            }
        }
        double playerWeight = getConfig().getDouble("voting.player-weight", 50.0);
        double judgeWeight = getConfig().getDouble("voting.judge-weight", 50.0);
        double weightTotal = playerWeight + judgeWeight;
        if (weightTotal <= 0) { playerWeight = 50; judgeWeight = 50; weightTotal = 100; }
        List<ResultRow> result = new ArrayList<>();
        for (Plot plot : owned) {
            int votes = voteCounts.getOrDefault(plot.id(), 0);
            double playerScore = totalVotes == 0 ? 0 : (votes * 100.0 / totalVotes);
            List<Integer> totals = judgeTotals.getOrDefault(plot.id(), List.of());
            double judgeScore = totals.isEmpty() ? 0 : totals.stream().mapToInt(Integer::intValue).average().orElse(0);
            double finalScore = ((playerScore * playerWeight) + (judgeScore * judgeWeight)) / weightTotal;
            result.add(new ResultRow(plot.id(), plot.ownerUuid(), plot.ownerName(), votes, playerScore, judgeScore, finalScore));
        }
        result.sort(Comparator.comparingDouble(ResultRow::finalScore).reversed()
                .thenComparing(Comparator.comparingInt(ResultRow::votes).reversed())
                .thenComparing(ResultRow::plotId, String.CASE_INSENSITIVE_ORDER));
        // Explicit admin overrides are applied after score calculation and before reward distribution.
        for (int rank = 1; rank <= 3; rank++) {
            String forced;
            try { forced = getMeta("winner_" + rank); } catch (SQLException ex) { forced = null; }
            if (forced == null || forced.isBlank()) continue;
            int found = -1;
            for (int i = 0; i < result.size(); i++) if (result.get(i).plotId().equalsIgnoreCase(forced)) { found = i; break; }
            if (found >= 0 && found != rank - 1) {
                ResultRow row = result.remove(found);
                result.add(Math.min(rank - 1, result.size()), row);
            }
        }
        return result;
    }

    private void showResults(CommandSender sender) {
        try {
            List<ResultRow> results = calculateResults();
            sender.sendMessage(color("&6---- ClasherCity Leaderboard ----"));
            sender.sendMessage(color("&7Tournament: &f" + tournamentName() + " &7| Phase: &f" + phase()));
            if (results.isEmpty()) { sender.sendMessage(color("&cNo owned plot entries yet.")); return; }
            for (int i = 0; i < results.size(); i++) {
                ResultRow r = results.get(i);
                sender.sendMessage(color("&e#" + (i + 1) + " &f" + r.ownerName() + " &7(" + r.plotId() + ") &8- &a"
                        + String.format(Locale.US, "%.2f", r.finalScore()) + " &7| votes " + r.votes()
                        + " | judge " + String.format(Locale.US, "%.1f", r.judgeScore())));
            }
        } catch (SQLException ex) { sender.sendMessage(msg("Could not calculate results.")); }
    }

    private void queueAndDeliverRewards(List<ResultRow> results) throws SQLException {
        String[] paths = {"rewards.first-commands", "rewards.second-commands", "rewards.third-commands"};
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            ResultRow row = results.get(i);
            List<String> commands = getConfig().getStringList(paths[i]);
            if (commands.isEmpty()) {
                getLogger().warning("No reward commands configured for rank " + (i + 1) + ". Set " + paths[i] + " in config.yml.");
                continue;
            }
            try (PreparedStatement ps = db.prepareStatement("INSERT OR IGNORE INTO reward_queue(tournament_id,player_uuid,player_name,rank,plot_id,status) VALUES(?,?,?,?,?,'PENDING')")) {
                ps.setString(1, tournamentId()); ps.setString(2, row.ownerUuid()); ps.setString(3, row.ownerName()); ps.setInt(4, i + 1); ps.setString(5, row.plotId()); ps.executeUpdate();
            }
            Player online = Bukkit.getPlayer(UUID.fromString(row.ownerUuid()));
            if (online != null) deliverPendingRewards(online);
            else broadcast(msg("Prize for " + row.ownerName() + " is pending until they next join."));
        }
    }

    private void deliverPendingRewards(Player player) {
        try (PreparedStatement ps = db.prepareStatement("SELECT tournament_id,rank,plot_id FROM reward_queue WHERE player_uuid=? AND status='PENDING' ORDER BY created_at,rank")) {
            ps.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<String> tournaments = new ArrayList<>();
                List<Integer> ranks = new ArrayList<>();
                List<String> plotIds = new ArrayList<>();
                while (rs.next()) { tournaments.add(rs.getString("tournament_id")); ranks.add(rs.getInt("rank")); plotIds.add(rs.getString("plot_id")); }
                for (int i = 0; i < ranks.size(); i++) {
                    String rewardTournament = tournaments.get(i);
                    int rank = ranks.get(i);
                    String plotId = plotIds.get(i);
                    String path = rank == 1 ? "rewards.first-commands" : rank == 2 ? "rewards.second-commands" : "rewards.third-commands";
                    List<String> commands = getConfig().getStringList(path);
                    if (commands.isEmpty()) {
                        try (PreparedStatement update = db.prepareStatement("UPDATE reward_queue SET status='REVIEW' WHERE tournament_id=? AND player_uuid=? AND rank=?")) {
                            update.setString(1, rewardTournament); update.setString(2, player.getUniqueId().toString()); update.setInt(3, rank); update.executeUpdate();
                        }
                        getLogger().warning("Reward commands are empty for rank " + rank + "; marked REVIEW for " + player.getName());
                        continue;
                    }
                    boolean dispatched = true;
                    for (String raw : commands) {
                        String command = raw.replace("{player}", player.getName()).replace("{uuid}", player.getUniqueId().toString())
                                .replace("{plot}", plotId).replace("{rank}", Integer.toString(rank)).trim();
                        if (command.startsWith("/")) command = command.substring(1);
                        try {
                            if (command.isBlank() || !Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) dispatched = false;
                        } catch (RuntimeException commandError) {
                            dispatched = false;
                            getLogger().warning("Reward command threw an error for " + player.getName() + ": " + commandError.getMessage());
                        }
                    }
                    if (dispatched) {
                        try (PreparedStatement update = db.prepareStatement("UPDATE reward_queue SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP WHERE tournament_id=? AND player_uuid=? AND rank=?")) {
                            update.setString(1, rewardTournament); update.setString(2, player.getUniqueId().toString()); update.setInt(3, rank); update.executeUpdate();
                        }
                        player.sendMessage(msg("Your rank " + rank + " tournament reward command(s) have been dispatched."));
                    } else {
                        try (PreparedStatement update = db.prepareStatement("UPDATE reward_queue SET status='REVIEW' WHERE tournament_id=? AND player_uuid=? AND rank=?")) {
                            update.setString(1, rewardTournament); update.setString(2, player.getUniqueId().toString()); update.setInt(3, rank); update.executeUpdate();
                        }
                        getLogger().warning("A reward command for " + player.getName() + " rank " + rank + " returned false. Marked REVIEW; verify plugin state before retrying.");
                        player.sendMessage(msg("Your reward command needs admin review. Please contact staff."));
                    }
                }
            }
        } catch (Exception ex) { getLogger().warning("Reward delivery failed: " + ex.getMessage()); }
    }

    private void rewardCommand(CommandSender sender, String[] args) throws SQLException {
        if (args.length >= 2 && args[1].equalsIgnoreCase("distribute")) {
            List<ResultRow> results = calculateResults();
            queueAndDeliverRewards(results);
            sender.sendMessage(msg("Configured rewards have been queued/dispatched for the current leaderboard."));
        } else if (args.length >= 3 && args[1].equalsIgnoreCase("retry")) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            try (PreparedStatement ps = db.prepareStatement("UPDATE reward_queue SET status='PENDING' WHERE player_uuid=? AND status IN ('PENDING','REVIEW')")) {
                ps.setString(1, target.getUniqueId().toString());
                int count = ps.executeUpdate(); sender.sendMessage(msg("Pending/review reward records reset: " + count + ". Verify no reward was already granted before retrying."));
                Player online = Bukkit.getPlayer(target.getUniqueId());
                if (online != null && count > 0) deliverPendingRewards(online);
            }
        } else sender.sendMessage(msg("Usage: /clcity reward <distribute|retry <player>>"));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> deliverPendingRewards(event.getPlayer()), 40L);
    }

    private void broadcast(String message) { Bukkit.broadcastMessage(message); }

    // ------------------------- Physical city map -------------------------

    private void givePhysicalMap(Player player) {
        if (cityWorld == null) { player.sendMessage(msg("City world is not loaded.")); return; }
        MapView view = Bukkit.createMap(cityWorld);
        for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) view.removeRenderer(renderer);
        view.setScale(MapView.Scale.CLOSEST);
        view.setTrackingPosition(false);
        view.addRenderer(mapRenderer);
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setMapView(view);
        meta.setDisplayName(color("&bClasherCity Map"));
        meta.setLore(List.of(color("&7Green: available plots"), color("&7Red: purchased plots"), color("&7Grey: roads"), color("&7Blue: public areas")));
        map.setItemMeta(meta);
        player.getInventory().addItem(map);
        player.sendMessage(msg("Physical city map given. Place it in an item frame at spawn."));
    }

    private void createMapWall(Player player, boolean create) {
        String worldName=getConfig().getString("map-wall.world");
        if(worldName==null){ player.sendMessage(msg("Select the map wall anchor first with /clcity mapwall select.")); return; }
        World world=Bukkit.getWorld(worldName);
        if(world==null){ player.sendMessage(msg("Map wall world is not loaded.")); return; }
        int baseX=getConfig().getInt("map-wall.x"), baseY=getConfig().getInt("map-wall.y"), baseZ=getConfig().getInt("map-wall.z");
        BlockFace facing;
        try { facing=BlockFace.valueOf(getConfig().getString("map-wall.facing","SOUTH")); }
        catch(IllegalArgumentException ex){ facing=BlockFace.SOUTH; }
        if(!facing.isCartesian() || facing.getModY()!=0){ player.sendMessage(msg("Map wall facing must be horizontal. Reselect while facing north, south, east or west.")); return; }
        final BlockFace wallFacing=facing;
        int rightX=-wallFacing.getModZ(), rightZ=wallFacing.getModX();
        if(create) for(org.bukkit.entity.Entity entity:world.getEntities()) if(entity instanceof org.bukkit.entity.ItemFrame && entity.getScoreboardTags().contains("clashercity_mapwall")) entity.remove();
        int frames=0, occupied=0;
        for(int row=0;row<4;row++) for(int col=0;col<4;col++){
            int x=baseX+rightX*col, y=baseY+row, z=baseZ+rightZ*col;
            Block support=world.getBlockAt(x-wallFacing.getModX(),y,z-wallFacing.getModZ());
            if(support.getType().isAir() || support.isPassable()){ occupied++; continue; }
            boolean existing=world.getNearbyEntities(new Location(world,x+0.5,y+0.5,z+0.5),0.35,0.35,0.35).stream().anyMatch(e->e instanceof org.bukkit.entity.ItemFrame);
            if(existing){ occupied++; continue; }
            frames++;
            if(create){
                final int tileX=col, tileY=3-row;
                org.bukkit.entity.ItemFrame frame=world.spawn(new Location(world,x+0.5,y+0.5,z+0.5),org.bukkit.entity.ItemFrame.class, f->{
                    f.setFacingDirection(wallFacing,true);
                    f.addScoreboardTag("clashercity_mapwall");
                    f.setVisible(true);
                    f.setFixed(true);
                });
                MapView view=Bukkit.createMap(world);
                for(MapRenderer renderer:new ArrayList<>(view.getRenderers())) view.removeRenderer(renderer);
                view.setScale(MapView.Scale.CLOSEST); view.setTrackingPosition(false);
                view.addRenderer(new MapTileRenderer(tileX,tileY));
                ItemStack map=new ItemStack(Material.FILLED_MAP);
                MapMeta meta=(MapMeta)map.getItemMeta(); meta.setMapView(view);
                meta.setDisplayName(color("&bClasherCity Atlas &7| &f"+(tileX+1)+"/4, "+(4-tileY)+"/4"));
                map.setItemMeta(meta); frame.setItem(map);
            }
        }
        if(create) player.sendMessage(msg("4x4 map wall created/updated: &f"+frames+"&a frames placed; &c"+occupied+"&a positions skipped (existing frame or missing support)."));
        else player.sendMessage(msg("Map wall preview: &f"+frames+"&a valid frame positions; &c"+occupied+"&a positions need a solid support wall or are occupied."));
    }

    private void removeMapWall(Player player) {
        String worldName=getConfig().getString("map-wall.world");
        World world=worldName==null?null:Bukkit.getWorld(worldName);
        if(world==null){ player.sendMessage(msg("Map wall world is not loaded.")); return; }
        int removed=0;
        for(org.bukkit.entity.Entity entity:world.getEntities()) if(entity instanceof org.bukkit.entity.ItemFrame && entity.getScoreboardTags().contains("clashercity_mapwall")) { entity.remove(); removed++; }
        player.sendMessage(msg("Removed &f"+removed+"&a ClasherCity map wall frames. Other item frames were untouched."));
    }

    private final class MapTileRenderer extends MapRenderer {
        private final int tileX, tileY;
        private BufferedImage cached;
        private long lastBuild;
        MapTileRenderer(int tileX,int tileY){ super(false); this.tileX=tileX; this.tileY=tileY; }
        @Override public void render(MapView view,MapCanvas canvas,Player player){
            if(cached==null || System.currentTimeMillis()-lastBuild>5000){
                BufferedImage full=buildMapImage();
                cached=new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);
                Graphics2D g=cached.createGraphics();
                try { g.drawImage(full,0,0,128,128,tileX*32,tileY*32,(tileX+1)*32,(tileY+1)*32,null); }
                finally { g.dispose(); }
                lastBuild=System.currentTimeMillis();
            }
            canvas.drawImage(0,0,cached);
        }
    }

    private final class MapViewRenderer extends MapRenderer {
        private BufferedImage cached;
        private long lastBuild;
        MapViewRenderer() { super(false); }
        void invalidate() { cached = null; lastBuild = 0; }
        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (cached == null || System.currentTimeMillis() - lastBuild > 5000) {
                cached = buildMapImage();
                lastBuild = System.currentTimeMillis();
            }
            canvas.drawImage(0, 0, cached);
        }
    }

    private BufferedImage buildMapImage() {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(31, 38, 45)); g.fillRect(0, 0, 128, 128);
            List<Plot> plots = getPlots();
            List<Area> areas = getAreas();
            int minX = 0, minZ = 0, maxX = 100, maxZ = 100;
            boolean any = false;
            for (Plot p : plots) { if (!any) { minX=p.minX(); maxX=p.maxX(); minZ=p.minZ(); maxZ=p.maxZ(); any=true; } else { minX=Math.min(minX,p.minX()); maxX=Math.max(maxX,p.maxX()); minZ=Math.min(minZ,p.minZ()); maxZ=Math.max(maxZ,p.maxZ()); } }
            for (Area a : areas) { if (!any) { minX=a.minX(); maxX=a.maxX(); minZ=a.minZ(); maxZ=a.maxZ(); any=true; } else { minX=Math.min(minX,a.minX()); maxX=Math.max(maxX,a.maxX()); minZ=Math.min(minZ,a.minZ()); maxZ=Math.max(maxZ,a.maxZ()); } }
            int width = Math.max(1, maxX-minX+1), depth = Math.max(1, maxZ-minZ+1);
            double scale = Math.min(116.0 / width, 104.0 / depth);
            g.setColor(new Color(230, 230, 230)); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
            g.drawString("CLASHER CITY", 5, 9);
            for (Area a : areas) {
                int x = 6 + (int)Math.round((a.minX()-minX)*scale), y = 15 + (int)Math.round((a.minZ()-minZ)*scale);
                int w = Math.max(2, (int)Math.ceil((a.maxX()-a.minX()+1)*scale)), h = Math.max(2, (int)Math.ceil((a.maxZ()-a.minZ()+1)*scale));
                g.setColor(a.type().equalsIgnoreCase("ROAD") ? new Color(115, 121, 128) : new Color(70, 125, 185));
                int clippedW = Math.max(0, Math.min(w, 122-x)), clippedH = Math.max(0, Math.min(h, 119-y));
                if (clippedW > 0 && clippedH > 0) g.fillRect(x, y, clippedW, clippedH);
            }
            for (Plot p : plots) {
                int x = 6 + (int)Math.round((p.minX()-minX)*scale), y = 15 + (int)Math.round((p.minZ()-minZ)*scale);
                int w = Math.max(2, (int)Math.ceil((p.maxX()-p.minX()+1)*scale)), h = Math.max(2, (int)Math.ceil((p.maxZ()-p.minZ()+1)*scale));
                int clippedW = Math.max(0, Math.min(w, 122-x)), clippedH = Math.max(0, Math.min(h, 119-y));
                g.setColor(p.ownerUuid() == null ? new Color(42, 170, 85) : new Color(185, 63, 63));
                if (clippedW > 0 && clippedH > 0) {
                    g.fillRect(x, y, clippedW, clippedH);
                    g.setColor(new Color(20, 25, 28)); g.setStroke(new BasicStroke(1)); g.drawRect(x, y, clippedW, clippedH);
                }
                if (w > 10 && h > 7) { g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 7)); g.drawString(p.id(), x+1, y+8); }
            }
            g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 7));
            g.drawString("Green: free  Red: sold", 5, 126);
        } catch (Exception ex) {
            g.setColor(Color.RED); g.drawString("MAP DATA ERROR", 8, 64);
        } finally { g.dispose(); }
        return image;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(keyAction, PersistentDataType.STRING);
        String plotId = clicked.getItemMeta().getPersistentDataContainer().get(keyPlot, PersistentDataType.STRING);
        if (action == null || action.equals("none")) return;
        try {
            switch (action) {
                case "close" -> player.closeInventory();
                case "visit" -> { player.closeInventory(); teleportCitySpawn(player); }
                case "plots" -> openPlotMenu(player, 0);
                case "map" -> openMapMenu(player, 0);
                case "destinations" -> openDestinationsMenu(player);
                case "destination" -> { if(plotId!=null) teleportDestination(player,plotId); }
                case "home" -> { player.closeInventory(); teleportHome(player, null); }
                case "vote" -> {
                    if (holder.type.equals("MAIN")) openVoteMenu(player);
                    else if (plotId != null) openVoteConfirm(player, plotId);
                }
                case "top" -> { player.closeInventory(); showResults(player); }
                case "plot_info" -> { if (plotId != null) openPlotInfoMenu(player, plotId); }
                case "confirm" -> { if (plotId != null) openPurchaseConfirm(player, plotId); }
                case "buy" -> { if (plotId != null) purchasePlot(player, plotId); }
                case "cancel" -> openPlotMenu(player, 0);
                case "enter" -> { if (plotId != null) { player.closeInventory(); enterPlot(player, plotId); } }
                case "map_prev" -> openMapMenu(player, holder.page - 1);
                case "map_next" -> openMapMenu(player, holder.page + 1);
                case "plot_prev" -> openPlotMenu(player, holder.page - 1);
                case "plot_next" -> openPlotMenu(player, holder.page + 1);
                case "vote_prev" -> openVoteMenu(player, holder.page - 1);
                case "vote_next" -> openVoteMenu(player, holder.page + 1);
                case "vote_submit" -> { if (plotId != null) castVote(player, plotId); }
                case "vote_back" -> openVoteMenu(player, 0);
                case "admin_create" -> { if (player.hasPermission("clashercity.admin")) { player.closeInventory(); if (cityWorld == null) loadCityWorld(); ensureGlobalRegion(cityWorld); player.sendMessage(msg("City world is ready.")); } }
                case "admin_wand" -> {
                    if (!player.hasPermission("clashercity.admin")) break;
                    ItemStack wand = new ItemStack(Material.BLAZE_ROD);
                    ItemMeta meta = wand.getItemMeta();
                    meta.setDisplayName(color("&6ClasherCity Selection Wand"));
                    meta.getPersistentDataContainer().set(keyWand, PersistentDataType.BYTE, (byte) 1);
                    wand.setItemMeta(meta);
                    player.getInventory().addItem(wand);
                    player.sendMessage(msg("Selection wand given. Left-click and right-click two corners."));
                }
                case "admin_map" -> { if (player.hasPermission("clashercity.admin")) givePhysicalMap(player); }
                case "admin_start" -> { if (player.hasPermission("clashercity.admin")) startTournament(player, "ClasherCity Building Championship"); }
                case "admin_building" -> { if (player.hasPermission("clashercity.admin")) { setPhase(Phase.BUILDING); broadcast(msg("Building phase is now open.")); openAdminMenu(player); } }
                case "admin_voting" -> { if (player.hasPermission("clashercity.admin")) { setPhase(Phase.VOTING); broadcast(msg("Voting is now open. Use &e/buildvote&a.")); openAdminMenu(player); } }
                case "admin_results" -> { if (player.hasPermission("clashercity.admin")) { player.closeInventory(); showResults(player); } }
                default -> { }
            }
        } catch (Exception ex) {
            getLogger().warning("Menu action '" + action + "' failed for " + player.getName() + ": " + ex.getMessage());
            player.sendMessage(msg("That menu action failed. Please contact staff."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    // ------------------------------- Events -------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSelectionWand(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta() || item.getItemMeta().getPersistentDataContainer().get(keyWand, PersistentDataType.BYTE) == null) return;
        if (event.getHand() != null && event.getHand().name().equals("OFF_HAND")) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!event.getPlayer().hasPermission("clashercity.admin")) return;
        event.setCancelled(true);
        if (event.getClickedBlock() == null || !isCityWorld(event.getClickedBlock().getWorld())) {
            event.getPlayer().sendMessage(msg("Selection wand only works in the city world.")); return;
        }
        Location[] pair = selections.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new Location[2]);
        int index = event.getAction() == Action.LEFT_CLICK_BLOCK ? 0 : 1;
        pair[index] = event.getClickedBlock().getLocation();
        event.getPlayer().sendMessage(msg("Position " + (index + 1) + " set to " + pair[index].getBlockX() + ", " + pair[index].getBlockY() + ", " + pair[index].getBlockZ()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isCityWorld(event.getBlock().getWorld()) && !mayBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true); event.getPlayer().sendMessage(msg("You can only build on your own plot during the building phase."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isCityWorld(event.getBlock().getWorld()) && !mayBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true); event.getPlayer().sendMessage(msg("You can only break blocks on your own plot during the building phase."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || !isCityWorld(event.getClickedBlock().getWorld())) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getItem() != null && event.getItem().getType() != Material.AIR) return;
        if (!event.getPlayer().hasPermission("clashercity.bypass") && !isPlotOwner(event.getPlayer(), event.getClickedBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (isCityWorld(event.getRightClicked().getWorld()) && !mayBuild(event.getPlayer(), event.getRightClicked().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (isCityWorld(event.getBlock().getWorld()) && !mayBuild(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player && isCityWorld(event.getEntity().getWorld()) && !mayBuild(player, event.getEntity().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isCityWorld(event.getBlock().getWorld()) && !mayBuild(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (isCityWorld(event.getBlock().getWorld()) && !mayBuild(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (!isCityWorld(event.getBlock().getWorld()) && !isCityWorld(event.getToBlock().getWorld())) return;
        Plot source = plotAt(event.getBlock().getLocation());
        Plot target = plotAt(event.getToBlock().getLocation());
        if (source == null || target == null || !source.id().equalsIgnoreCase(target.id())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) { if (isCityWorld(event.getLocation().getWorld())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) { if (isCityWorld(event.getBlock().getWorld())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) { if (isCityWorld(event.getBlock().getWorld())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) { if (isCityWorld(event.getBlock().getWorld())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) { if (isCityWorld(event.getBlock().getWorld())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) { if (isCityWorld(event.getBlock().getWorld())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) { if (isCityWorld(event.getBlock().getWorld())) event.setCancelled(true); }
}
