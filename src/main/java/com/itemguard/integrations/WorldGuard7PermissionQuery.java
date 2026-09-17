package com.itemguard.integrations;

import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import org.bukkit.entity.Player;

final class WorldGuard7PermissionQuery implements WorldGuardPermissionQuery {

    @Override
    public boolean hasTrackPermission(Player player) {
        if (player == null) return false;
        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        return localPlayer != null
            && localPlayer.hasPermission("itemguard.track");
    }
}