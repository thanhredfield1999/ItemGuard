package com.itemguard.tasks;

import com.itemguard.ItemGuard;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

public class CleanupTask extends BukkitRunnable {

    private final ItemGuard plugin;

    public CleanupTask(ItemGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        int keepDays = plugin.getConfigs().getCleanupKeepDays();
        if (keepDays <= 0) return;

        plugin.getLogger().info("Running database cleanup...");
        plugin.getDB().deleteOldHistory(keepDays);
    }
}
