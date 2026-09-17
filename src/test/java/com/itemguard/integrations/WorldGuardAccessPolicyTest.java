package com.itemguard.integrations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGuardAccessPolicyTest {

    private final WorldGuardAccessPolicy policy = new WorldGuardAccessPolicy();

    @Test
    void disabledIntegrationDoesNotBlockTracking() {
        assertTrue(policy.canTrack(false, false, false, false));
    }

    @Test
    void configuredIntegrationFailsClosedWhenAnyCapabilityIsUnavailable() {
        assertFalse(policy.canTrack(true, false, false, false));
        assertFalse(policy.canTrack(true, true, false, false));
        assertFalse(policy.canTrack(true, true, true, false));
    }

    @Test
    void configuredIntegrationAllowsOnlySuccessfulPermissionDecision() {
        assertTrue(policy.canTrack(true, true, true, true));
    }
}
