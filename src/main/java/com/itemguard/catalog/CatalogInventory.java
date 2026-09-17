package com.itemguard.catalog;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.event.inventory.InventoryClickEvent;
import java.util.UUID;
import java.util.function.Function;

public final class CatalogInventory implements InventoryHolder {
    private final UUID viewer;
    private final CatalogScreen screen;
    private final Inventory inventory;
    public CatalogInventory(UUID viewer, CatalogScreen screen, Function<InventoryHolder, Inventory> factory) {
        this.viewer=viewer; this.screen=screen; this.inventory=factory.apply(this);
    }
    @Override public Inventory getInventory() { return inventory; }
    public Runnable cancelAndResolve(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!viewer.equals(event.getWhoClicked().getUniqueId())
            || event.getView().getTopInventory() != inventory
            || event.getClick() != org.bukkit.event.inventory.ClickType.LEFT
            || event instanceof org.bukkit.event.inventory.InventoryCreativeEvent
            || event.getRawSlot() < 0 || event.getRawSlot() >= 54) return null;
        var icon = screen.icons().get(event.getRawSlot());
        return icon == null ? null : icon.action();
    }
}
