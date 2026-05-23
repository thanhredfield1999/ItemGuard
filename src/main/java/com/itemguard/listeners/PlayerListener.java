package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public class PlayerListener implements Listener {

    private final ItemGuard plugin;

    public PlayerListener(ItemGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.getTrackingService().scanPlayerInventory(player);
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getTrackingService().onItemDeath(player);
    }
}
