package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPublicationLifecyclePolicyTest {

    private final EntityPublicationLifecyclePolicy policy =
        new EntityPublicationLifecyclePolicy();

    @Test
    void spawnEventCaptureIsAlwaysDeferred() {
        assertEquals(
            EntityPublicationCaptureAction.DEFER,
            policy.captureAction(true, false, 0, 5)
        );
        assertEquals(
            EntityPublicationCaptureAction.DEFER,
            policy.captureAction(true, true, 0, 5)
        );
    }

    @Test
    void deferredCaptureRequestsWhenEntityBecomesValid() {
        assertEquals(
            EntityPublicationCaptureAction.REQUEST,
            policy.captureAction(false, true, 1, 5)
        );
    }

    @Test
    void deferredCaptureRetriesWhileBudgetRemains() {
        assertEquals(
            EntityPublicationCaptureAction.DEFER,
            policy.captureAction(false, false, 1, 5)
        );
        assertEquals(
            EntityPublicationCaptureAction.IGNORE,
            policy.captureAction(false, false, 5, 5)
        );
    }

    @Test
    void physicalWriteRequiresValidEntityAndMatchingDigest() {
        assertTrue(policy.canWrite(true, true));
        assertFalse(policy.canWrite(false, true));
        assertFalse(policy.canWrite(true, false));
    }
}
