package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroundItemMergePolicyTest {

    private final GroundItemMergePolicy policy = new GroundItemMergePolicy();

    @Test
    void twoUntaggedStacksMayUseVanillaMerge() {
        assertEquals(
            GroundItemMergePolicy.Action.ALLOW,
            policy.decide(false, false)
        );
    }

    @Test
    void identityOnMergeSourceFailsClosed() {
        assertEquals(
            GroundItemMergePolicy.Action.CANCEL,
            policy.decide(true, false)
        );
    }

    @Test
    void identityOnMergeTargetFailsClosed() {
        assertEquals(
            GroundItemMergePolicy.Action.CANCEL,
            policy.decide(false, true)
        );
    }

    @Test
    void exactIdentityCopiesCannotCoalesce() {
        assertEquals(
            GroundItemMergePolicy.Action.CANCEL,
            policy.decide(true, true)
        );
    }
}
