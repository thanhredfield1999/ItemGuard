package com.itemguard.tasks;

import com.itemguard.ItemGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class InventoryScanTask extends BukkitRunnable {

    private final ItemGuard plugin;

    public InventoryScanTask(ItemGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getTrackingService().scanPlayerInventory(player);
        }
    }
}
