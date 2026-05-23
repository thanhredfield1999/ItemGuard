package com.itemguard.integrations;

import com.itemguard.ItemGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.logging.Level;

public class WorldGuardHook {

    private final ItemGuard plugin;
    private boolean hooked = false;
    private Object regionContainer;

    public WorldGuardHook(ItemGuard plugin) {
        this.plugin = plugin;
        if (plugin.getConfigs().isWorldGuardEnabled()) {
            tryHook();
        }
    }

    private void tryHook() {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            return;
        }

        try {
            Class<?> wgPluginClass = Bukkit.getPluginManager().getPlugin("WorldGuard").getClass();
            java.lang.reflect.Method getServerMethod = wgPluginClass.getMethod("getServer");
            Object server = getServerMethod.invoke(Bukkit.getPluginManager().getPlugin("WorldGuard"));
            Class<?> serverClass = server.getClass();
            regionContainer = serverClass.getMethod("getRegionContainer").invoke(server);

            hooked = true;
            plugin.getLogger().info("WorldGuard hook enabled.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "WorldGuard hook failed: " + e.getMessage());
        }
    }

    public boolean isHooked() {
        return hooked;
    }

    public boolean canTrack(Player player) {
        if (!hooked) return true;
        if (regionContainer == null) return true;

        try {
            Class<?> regionContainerClass = regionContainer.getClass();
            Object wgWorld = regionContainerClass.getMethod("get", String.class)
                    .invoke(regionContainer, player.getWorld().getName());

            if (wgWorld == null) return true;

            Object wgPlayer = wgWorld.getClass().getMethod("createPlayer", String.class)
                    .invoke(wgWorld, player.getName());

            Class<?> wgPlayerClass = wgPlayer.getClass();
            Boolean allowed = (Boolean) wgPlayerClass.getMethod("hasPermission", String.class)
                    .invoke(wgPlayer, "itemguard.track");
            return allowed != null && allowed;
        } catch (Exception e) {
            return true;
        }
    }
}
