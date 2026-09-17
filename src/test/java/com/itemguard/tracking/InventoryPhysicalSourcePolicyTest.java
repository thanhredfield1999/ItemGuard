package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryPhysicalSourcePolicyTest {

    private final InventoryPhysicalSourcePolicy policy =
        new InventoryPhysicalSourcePolicy();

    @Test
    void acceptsNewBukkitMirrorForSamePhysicalHandleAndDigest() {
        Object physicalHandle = new Object();
        byte[] digest = {1, 2, 3};

        assertTrue(policy.matches(
            physicalHandle,
            physicalHandle,
            digest,
            digest.clone()
        ));
    }

    @Test
    void rejectsIdenticalReplacementWithDifferentPhysicalHandle() {
        byte[] digest = {1, 2, 3};

        assertFalse(policy.matches(
            new Object(),
            new Object(),
            digest,
            digest.clone()
        ));
    }

    @Test
    void rejectsSameHandleWhenDigestChanges() {
        Object physicalHandle = new Object();

        assertFalse(policy.matches(
            physicalHandle,
            physicalHandle,
            new byte[] {1, 2, 3},
            new byte[] {1, 2, 4}
        ));
    }

    @Test
    void rejectsMatchingHandleAndDigestWhenSourceIsNotAuthoritative() {
        Object physicalHandle = new Object();
        byte[] digest = {1, 2, 3};

        assertFalse(policy.matches(
            false,
            physicalHandle,
            physicalHandle,
            digest,
            digest.clone()
        ));
        assertTrue(policy.matches(
            true,
            physicalHandle,
            physicalHandle,
            digest,
            digest.clone()
        ));
    }

    @Test
    void rejectsMissingPhysicalHandle() {
        byte[] digest = {1};

        assertFalse(policy.matches(null, new Object(), digest, digest));
        assertFalse(policy.matches(new Object(), null, digest, digest));
    }

    @Test
    void locatesOnlyTheSamePhysicalHandle() {
        Object expected = new Object();

        assertTrue(policy.samePhysicalHandle(expected, expected));
        assertFalse(policy.samePhysicalHandle(expected, new Object()));
        assertFalse(policy.samePhysicalHandle(null, expected));
    }
}
