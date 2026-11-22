package network.clasher.clashercity.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Secure InventoryHolder for all ClasherCity menus with pagination metadata.
 */
public final class CityMenuHolder implements InventoryHolder {
    public enum MenuType {
        MAIN,
        ADMIN,
        PLOTS,
        PLOT_INFO,
        CONFIRM,
        MAP,
        VOTE
    }

    private final MenuType type;
    private final int page;
    private Inventory inventory;

    public CityMenuHolder(MenuType type, int page) {
        this.type = type;
        this.page = page;
    }

    public MenuType type() { return type; }
    public int page() { return page; }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
