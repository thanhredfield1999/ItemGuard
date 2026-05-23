package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;

public class CraftListener implements Listener {

    private final ItemGuard plugin;
    private final ItemTrackingService tracking;

    public CraftListener(ItemGuard plugin) {
        this.plugin = plugin;
        this.tracking = plugin.getTrackingService();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack result = event.getInventory().getResult();
        if (result == null) return;

        if (tracking.shouldTrack(result) && !tracking.hasCode(result)) {
            ItemStack tagged = tracking.tagItem(result, player);
            event.getInventory().setResult(tagged);
            tracking.onItemCraft(tagged, player);
        }
    }
}
