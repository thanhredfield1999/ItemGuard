package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemIdentityEligibilityPolicyTest {

    private final ItemIdentityEligibilityPolicy policy =
        new ItemIdentityEligibilityPolicy();

    @Test
    void nonStackableItemCanBeTrackedWhenEnabled() {
        assertTrue(policy.shouldTrack(1, 1, true, true, false, false));
    }

    @Test
    void disabledTrackingRejectsNonStackableItem() {
        assertFalse(policy.shouldTrack(1, 1, false, true, false, true));
    }

    @Test
    void stackableConfigCannotEnableUuidPerItemIdentity() {
        assertFalse(policy.shouldTrack(64, 2, true, true, true, false));
    }

    @Test
    void forceTrackedMaterialCannotBypassStackableGuard() {
        assertFalse(policy.shouldTrack(64, 1, true, true, false, true));
    }

    @Test
    void legacyTaggedStackableIdentityIsNotOperationallyReady() {
        assertFalse(policy.supportsIdentity(64, 1));
        assertTrue(policy.supportsIdentity(1, 1));
    }

    @Test
    void invalidMaximumStackSizeIsRejected() {
        assertFalse(policy.shouldTrack(0, 1, true, true, false, true));
        assertFalse(policy.supportsIdentity(0, 1));
    }

    @Test
    void malformedNonStackableAmountCannotReceiveOrUseIdentity() {
        assertFalse(policy.shouldTrack(1, 2, true, true, false, true));
        assertFalse(policy.supportsIdentity(1, 2));
    }
}
