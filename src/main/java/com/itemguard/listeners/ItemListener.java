package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class ItemListener implements Listener {

    private final ItemGuard plugin;
    private final ItemTrackingService tracking;

    public ItemListener(ItemGuard plugin) {
        this.plugin = plugin;
        this.tracking = plugin.getTrackingService();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemPickup(PlayerPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        Player player = event.getPlayer();

        String code = tracking.getCodeFromItem(item);
        UUID itemUuid = tracking.getItemUuidFromItem(item);

        if (code != null && itemUuid != null) {
            tracking.onItemPickup(item, player);
        } else if (tracking.shouldTrack(item)) {
            ItemStack tagged = tracking.trackItem(item, player);
            event.getItem().setItemStack(tagged);
            tracking.onItemPickup(tagged, player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        Player player = event.getPlayer();

        if (tracking.hasCode(item)) {
            tracking.onItemDrop(item, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        if (tracking.hasCode(item)) {
            tracking.onItemMoveInInventory(item, player, "INVENTORY_MOVE");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        for (int slot : event.getRawSlots()) {
            ItemStack item = event.getNewItems().get(slot);
            if (item == null || item.getType() == Material.AIR) continue;
            if (tracking.hasCode(item)) {
                tracking.onItemMoveInInventory(item, player, "INVENTORY_DRAG");
            } else if (tracking.shouldTrack(item)) {
                tracking.trackItem(item, player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;

        if (tracking.hasCode(item)) {
            if (event.getAction().name().contains("RIGHT")) {
                tracking.onItemUse(item, player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack item = event.getEntity().getItemStack();
        if (tracking.shouldTrack(item) && !tracking.hasCode(item)) {
            ItemStack tagged = tracking.tagItem(item, null);
            event.getEntity().setItemStack(tagged);
        }
    }
}
