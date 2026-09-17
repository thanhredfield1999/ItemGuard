package com.itemguard.integrations;

import com.itemguard.ItemGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.logging.Level;

public class WorldGuardHook {

    private final ItemGuard plugin;
    private final WorldGuardAccessPolicy accessPolicy = new WorldGuardAccessPolicy();
    private final boolean integrationEnabled;
    private boolean hooked = false;
    private WorldGuardPermissionQuery permissionQuery;

    public WorldGuardHook(ItemGuard plugin) {
        this.plugin = plugin;
        this.integrationEnabled = plugin.getConfigs().isWorldGuardEnabled();
        if (integrationEnabled) {
            tryHook();
        }
    }

    private void tryHook() {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            return;
        }

        try {
            permissionQuery = new WorldGuard7PermissionQuery();
            hooked = true;
            plugin.getLogger().info("WorldGuard 7 permission hook enabled.");
        } catch (RuntimeException | LinkageError failure) {
            hooked = false;
            permissionQuery = null;
            plugin.getLogger().log(
                Level.WARNING,
                "WorldGuard 7 hook failed; tracking remains fail-closed",
                failure
            );
        }
    }

    public boolean isHooked() {
        return hooked;
    }

    public boolean canTrack(Player player) {
        if (!integrationEnabled) return true;
        if (!hooked || permissionQuery == null || player == null) return false;

        try {
            boolean allowed = permissionQuery.hasTrackPermission(player);
            return accessPolicy.canTrack(true, true, true, allowed);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }
}
