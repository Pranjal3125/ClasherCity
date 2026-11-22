package network.clasher.clashercity.listener;

import network.clasher.clashercity.ClasherCityPlugin;
import network.clasher.clashercity.model.Plot;
import network.clasher.clashercity.world.CityWorldService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Enforces city-world plot ownership, tournament building phase rules, selection wand input,
 * anti-grief protections, and login reward delivery.
 */
public final class CityProtectionListener implements Listener {
    private final ClasherCityPlugin plugin;
    private final CityWorldService worldService;
    private final NamespacedKey keyWand;

    public CityProtectionListener(ClasherCityPlugin plugin, CityWorldService worldService, NamespacedKey keyWand) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.keyWand = keyWand;
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(keyWand, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                plugin.deliverPendingRewards(event.getPlayer());
            }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSelectionWand(PlayerInteractEvent event) {
        if (!isWand(event.getItem())) return;
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!event.getPlayer().hasPermission("clashercity.admin")) return;
        event.setCancelled(true);
        if (event.getClickedBlock() == null || !worldService.isCityWorld(event.getClickedBlock().getWorld())) {
            event.getPlayer().sendMessage(plugin.msg("<red>Selection wand only works in the city world.</red>"));
            return;
        }
        int index = event.getAction() == Action.LEFT_CLICK_BLOCK ? 0 : 1;
        Location loc = event.getClickedBlock().getLocation();
        plugin.setSelectionCorner(event.getPlayer().getUniqueId(), index, loc);
        event.getPlayer().sendMessage(plugin.msg("<green>Position " + (index + 1) + " set to <yellow>"
                + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "</yellow>.</green>"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (worldService.isCityWorld(event.getBlock().getWorld())
                && !plugin.mayBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.msg("<red>You can only build on your own entered plot during the BUILDING phase.</red>"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (worldService.isCityWorld(event.getBlock().getWorld())
                && !plugin.mayBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.msg("<red>You can only break blocks on your own entered plot during the BUILDING phase.</red>"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || !worldService.isCityWorld(event.getClickedBlock().getWorld())) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (isWand(event.getItem())) return;
        if (!event.getPlayer().hasPermission("clashercity.bypass")
                && !plugin.isPlotOwner(event.getPlayer(), event.getClickedBlock().getLocation())) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (worldService.isCityWorld(event.getRightClicked().getWorld())
                && !plugin.mayBuild(event.getPlayer(), event.getRightClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && worldService.isCityWorld(event.getBlock().getWorld())
                && !plugin.mayBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player
                && worldService.isCityWorld(event.getEntity().getWorld())
                && !plugin.mayBuild(player, event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (worldService.isCityWorld(event.getBlock().getWorld())
                && !plugin.mayBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (worldService.isCityWorld(event.getBlock().getWorld())
                && !plugin.mayBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (!worldService.isCityWorld(event.getBlock().getWorld())
                && !worldService.isCityWorld(event.getToBlock().getWorld())) {
            return;
        }
        Plot source = plugin.plotAt(event.getBlock().getLocation());
        Plot target = plugin.plotAt(event.getToBlock().getLocation());
        if (source == null || target == null || !source.id().equalsIgnoreCase(target.id())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (worldService.isCityWorld(event.getLocation().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (worldService.isCityWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (worldService.isCityWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (worldService.isCityWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (worldService.isCityWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (worldService.isCityWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (worldService.isCityWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }
}
