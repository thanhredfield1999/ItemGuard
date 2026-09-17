package com.itemguard.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PickupIdentityPolicyTest {

    private final PickupIdentityPolicy policy = new PickupIdentityPolicy();

    @Test
    void completeIdentityIsRecordedWithoutRetagging() {
        assertEquals(PickupIdentityAction.RECORD, policy.resolve(IdentityTagStatus.COMPLETE));
    }

    @Test
    void absentIdentityMustBeTaggedAtItsSource() {
        assertEquals(PickupIdentityAction.TAG_SOURCE, policy.resolve(IdentityTagStatus.ABSENT));
    }

    @Test
    void corruptIdentityIsIgnoredUntilAuditedRepair() {
        assertEquals(PickupIdentityAction.IGNORE_CORRUPT, policy.resolve(IdentityTagStatus.CORRUPT));
    }
}
