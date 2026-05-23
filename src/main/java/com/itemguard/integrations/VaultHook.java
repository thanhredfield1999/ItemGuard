package com.itemguard.integrations;

import com.itemguard.ItemGuard;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.logging.Level;

public class VaultHook {

    private final ItemGuard plugin;
    private Permission vaultPermission;
    private boolean hooked = false;

    public VaultHook(ItemGuard plugin) {
        this.plugin = plugin;
        tryHook();
    }

    private void tryHook() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault")) {
            plugin.getLogger().info("Vault not found - using built-in permission checks.");
            return;
        }

        RegisteredServiceProvider<Permission> rsp = plugin.getServer()
                .getServicesManager()
                .getRegistration(Permission.class);

        if (rsp == null) {
            plugin.getLogger().info("Vault permission provider not found.");
            return;
        }

        vaultPermission = rsp.getProvider();
        hooked = true;
        plugin.getLogger().info("Vault permission hook enabled (" + vaultPermission.getName() + ").");
    }

    public boolean isHooked() {
        return hooked;
    }

    public boolean hasPermission(Player player, String permission) {
        if (!hooked) return player.hasPermission(permission);
        try {
            return vaultPermission.has(player, permission);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Vault permission check failed: " + e.getMessage());
            return player.hasPermission(permission);
        }
    }

    public boolean groupHasPermission(String group, String permission) {
        if (!hooked) return false;
        try {
            return vaultPermission.groupHas((String) null, group, permission);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Vault group permission check failed: " + e.getMessage());
            return false;
        }
    }
}
