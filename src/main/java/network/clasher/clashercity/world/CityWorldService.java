package network.clasher.clashercity.world;

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
import network.clasher.clashercity.model.Area;
import network.clasher.clashercity.model.Plot;
import network.clasher.clashercity.storage.CityDatabase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;

/**
 * Manages the dedicated flat city world and WorldGuard region protection for plots, roads, and public areas.
 */
public final class CityWorldService {
    private final JavaPlugin plugin;
    private World cityWorld;

    public CityWorldService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public World cityWorld() {
        return cityWorld;
    }

    public boolean isCityWorld(World world) {
        return cityWorld != null && world != null && world.getUID().equals(cityWorld.getUID());
    }

    public World loadCityWorld() {
        String worldName = plugin.getConfig().getString("city.world", "clasher_city");
        cityWorld = Bukkit.getWorld(worldName);
        if (cityWorld == null && plugin.getConfig().getBoolean("city.auto-load-world", true)) {
            WorldCreator creator = new WorldCreator(worldName)
                    .type(WorldType.FLAT)
                    .environment(World.Environment.NORMAL)
                    .generateStructures(false);
            cityWorld = Bukkit.createWorld(creator);
        }
        if (cityWorld == null) {
            throw new IllegalStateException("Could not load/create city world " + worldName);
        }
        cityWorld.setAutoSave(true);
        if (plugin.getConfig().getBoolean("city.spawn-set", false)) {
            Location configuredSpawn = new Location(
                    cityWorld,
                    plugin.getConfig().getDouble("city.spawn.x", 0.5),
                    plugin.getConfig().getDouble("city.spawn.y", cityWorld.getHighestBlockYAt(0, 0) + 1),
                    plugin.getConfig().getDouble("city.spawn.z", 0.5),
                    (float) plugin.getConfig().getDouble("city.spawn.yaw", 0.0),
                    (float) plugin.getConfig().getDouble("city.spawn.pitch", 0.0)
            );
            cityWorld.setSpawnLocation(configuredSpawn);
        } else {
            int y = cityWorld.getHighestBlockYAt(0, 0) + 1;
            cityWorld.setSpawnLocation(0, y, 0);
        }
        return cityWorld;
    }

    public RegionManager regionManager(World world) {
        if (world == null) return null;
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
    }

    public void ensureGlobalRegion(World world) throws Exception {
        RegionManager manager = regionManager(world);
        if (manager == null) {
            throw new IllegalStateException("WorldGuard region manager unavailable for " + world.getName());
        }
        ProtectedRegion global = manager.getRegion("__global__");
        if (global == null) {
            global = new GlobalProtectedRegion("__global__");
            manager.addRegion(global);
        }
        if (plugin.getConfig().getBoolean("protection.global-build-deny", true)) {
            global.setFlag(Flags.BUILD, StateFlag.State.DENY);
            global.setFlag(Flags.INTERACT, StateFlag.State.DENY);
            global.setFlag(Flags.CHEST_ACCESS, StateFlag.State.DENY);
        }
        global.setFlag(Flags.PVP, StateFlag.State.DENY);
        global.setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
        global.setFlag(Flags.TNT, StateFlag.State.DENY);
        global.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY);
        manager.saveChanges();
    }

    public void restoreRegions(CityDatabase db) throws Exception {
        if (cityWorld == null) return;
        for (Plot plot : db.getPlots()) {
            if (!plot.world().equals(cityWorld.getName())) continue;
            ProtectedRegion region = getOrCreatePlotRegion(plot);
            region.getOwners().clear();
            if (plot.isOwned()) {
                region.getOwners().addPlayer(UUID.fromString(plot.ownerUuid()));
            }
        }
        for (Area area : db.getAreas()) {
            if (!area.world().equals(cityWorld.getName())) continue;
            getOrCreateAreaRegion(area);
        }
        RegionManager manager = regionManager(cityWorld);
        if (manager != null) manager.saveChanges();
    }

    public ProtectedRegion getOrCreatePlotRegion(Plot plot) {
        RegionManager manager = regionManager(cityWorld);
        if (manager == null) throw new IllegalStateException("WorldGuard RegionManager unavailable");
        String regionId = plotRegionId(plot.id());
        ProtectedRegion region = manager.getRegion(regionId);
        if (region == null) {
            region = new ProtectedCuboidRegion(
                    regionId,
                    BlockVector3.at(plot.minX(), cityWorld.getMinHeight(), plot.minZ()),
                    BlockVector3.at(plot.maxX(), cityWorld.getMaxHeight() - 1, plot.maxZ())
            );
            manager.addRegion(region);
        }
        region.setPriority(10);
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

    public ProtectedRegion getOrCreateAreaRegion(Area area) {
        RegionManager manager = regionManager(cityWorld);
        if (manager == null) throw new IllegalStateException("WorldGuard RegionManager unavailable");
        String id = areaRegionId(area.type(), area.id());
        ProtectedRegion region = manager.getRegion(id);
        if (region == null) {
            region = new ProtectedCuboidRegion(
                    id,
                    BlockVector3.at(area.minX(), cityWorld.getMinHeight(), area.minZ()),
                    BlockVector3.at(area.maxX(), cityWorld.getMaxHeight() - 1, area.maxZ())
            );
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

    public String plotRegionId(String id) {
        return plugin.getConfig().getString("protection.plot-region-prefix", "plot_") + id.toLowerCase(Locale.ROOT);
    }

    public String areaRegionId(String type, String id) {
        String prefix = type.equalsIgnoreCase("ROAD")
                ? plugin.getConfig().getString("protection.road-region-prefix", "road_")
                : plugin.getConfig().getString("protection.public-region-prefix", "public_");
        return prefix + id.toLowerCase(Locale.ROOT);
    }

    public boolean teleportToPlot(Player player, Plot plot) {
        World world = Bukkit.getWorld(plot.world());
        if (world == null) return false;
        int x = (plot.minX() + plot.maxX()) / 2;
        int z = (plot.minZ() + plot.maxZ()) / 2;
        Location target = new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1.0, z + 0.5);
        player.teleport(target);
        return true;
    }
}
