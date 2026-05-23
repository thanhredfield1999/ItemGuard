package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ContainerListener implements Listener {

    private final ItemGuard plugin;
    private final ItemTrackingService tracking;
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    public ContainerListener(ItemGuard plugin) {
        this.plugin = plugin;
        this.tracking = plugin.getTrackingService();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (!plugin.getConfigs().isContainerScanEnabled()) return;
        if (event.isCancelled()) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (!tracking.shouldTrack(item)) return;
        if (tracking.hasCode(item)) return;

        if (!isCooldownActive(event.getSource().getHolder())) {
            ItemStack tagged = tracking.tagItem(item, null);
            event.setItem(tagged);
            setCooldown(event.getSource().getHolder());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!plugin.getConfigs().isContainerScanEnabled()) return;
        if (event.isCancelled()) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        if (isCooldownActive(event.getInventory().getHolder())) return;

        List<ItemStack> tagged = tracking.scanContainerInventory(event.getInventory());
        if (!tagged.isEmpty()) {
            setCooldown(event.getInventory().getHolder());
        }
    }

    private boolean isCooldownActive(Object holder) {
        if (holder == null) return false;
        String key = holder.toString();
        Long last = cooldowns.get(key);
        if (last == null) return false;
        return System.currentTimeMillis() - last < 500;
    }

    private void setCooldown(Object holder) {
        if (holder == null) return;
        cooldowns.put(holder.toString(), System.currentTimeMillis());
    }
}
