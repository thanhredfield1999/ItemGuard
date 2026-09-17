package com.itemguard.integrations;

import org.bukkit.entity.Player;

@FunctionalInterface
interface WorldGuardPermissionQuery {

    boolean hasTrackPermission(Player player);
}