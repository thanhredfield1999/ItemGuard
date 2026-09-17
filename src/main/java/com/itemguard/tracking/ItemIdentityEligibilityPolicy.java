package com.itemguard.tracking;

public final class ItemIdentityEligibilityPolicy {

    public boolean shouldTrack(
        int maximumStackSize,
        int amount,
        boolean trackingEnabled,
        boolean trackNonStackable,
        boolean trackStackable,
        boolean forceTrackedMaterial
    ) {
        if (!supportsIdentity(maximumStackSize, amount) || !trackingEnabled) {
            return false;
        }
        return trackNonStackable || forceTrackedMaterial;
    }

    public boolean supportsIdentity(int maximumStackSize, int amount) {
        return maximumStackSize == 1 && amount == 1;
    }
}
