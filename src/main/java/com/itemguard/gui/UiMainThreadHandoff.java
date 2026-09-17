package com.itemguard.gui;

import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

public final class UiMainThreadHandoff {

    private UiMainThreadHandoff() {
    }

    public static boolean dispatch(JavaPlugin plugin, Runnable task) {
        return submit(
            plugin.isEnabled(),
            () -> Bukkit.getScheduler().runTask(plugin, task)
        );
    }

    static boolean submit(boolean pluginEnabled, Runnable submitter) {
        if (!pluginEnabled) {
            return false;
        }
        try {
            submitter.run();
            return true;
        } catch (IllegalPluginAccessException disableRace) {
            return false;
        }
    }
}
