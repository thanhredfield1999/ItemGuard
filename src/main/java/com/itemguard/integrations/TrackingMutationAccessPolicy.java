package com.itemguard.integrations;

public final class TrackingMutationAccessPolicy {

    public boolean canMutate(
        boolean worldKnown,
        boolean worldEnabled,
        boolean worldGuardEnabled,
        boolean actorAvailable,
        boolean worldGuardAllowed
    ) {
        if (!worldKnown || !worldEnabled) {
            return false;
        }
        if (!worldGuardEnabled) {
            return true;
        }
        return actorAvailable && worldGuardAllowed;
    }
}
