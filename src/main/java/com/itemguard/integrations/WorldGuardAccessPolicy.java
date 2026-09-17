package com.itemguard.integrations;

public final class WorldGuardAccessPolicy {

    public boolean canTrack(
        boolean integrationEnabled,
        boolean hookAvailable,
        boolean queryAvailable,
        boolean permissionAllowed
    ) {
        if (!integrationEnabled) {
            return true;
        }
        return hookAvailable && queryAvailable && permissionAllowed;
    }
}
