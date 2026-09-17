package com.itemguard.integrations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackingMutationAccessPolicyTest {

    private final TrackingMutationAccessPolicy policy = new TrackingMutationAccessPolicy();

    @Test
    void missingOrDisabledWorldAlwaysDeniesMutation() {
        assertFalse(policy.canMutate(false, false, false, false, false));
        assertFalse(policy.canMutate(true, false, true, true, true));
    }

    @Test
    void disabledWorldGuardIntegrationAllowsMutationWithoutActor() {
        assertTrue(policy.canMutate(true, true, false, false, false));
    }

    @Test
    void enabledWorldGuardRequiresActorAndSuccessfulDecision() {
        assertFalse(policy.canMutate(true, true, true, false, false));
        assertFalse(policy.canMutate(true, true, true, true, false));
        assertTrue(policy.canMutate(true, true, true, true, true));
    }
}
