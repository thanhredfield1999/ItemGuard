package com.itemguard.tasks;

import com.itemguard.ItemGuard;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Level;

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
        plugin.getDB().deleteOldHistoryAsync(keepDays).whenComplete((deleted, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE, "ItemGuard database cleanup failed", failure);
                return;
            }
            plugin.getLogger().info(
                "Deleted " + deleted + " old history entries (older than " + keepDays + " days)"
            );
        });
    }
}
