package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HopperTransferPolicyTest {

    private final HopperTransferPolicy policy = new HopperTransferPolicy();

    @Test
    void absentEligibleIdentityCancelsUntilSourcePublicationCanComplete() {
        assertEquals(
            HopperTransferPolicy.Action.CANCEL_AND_SCAN_SOURCE,
            policy.decide(false, false, true)
        );
    }

    @Test
    void completeReadyIdentityCanMove() {
        assertEquals(
            HopperTransferPolicy.Action.ALLOW,
            policy.decide(true, true, true)
        );
    }

    @Test
    void taggedIdentityThatIsNotReadyFailsClosed() {
        assertEquals(
            HopperTransferPolicy.Action.CANCEL,
            policy.decide(true, false, true)
        );
    }

    @Test
    void ineligibleUntaggedItemIsIgnored() {
        assertEquals(
            HopperTransferPolicy.Action.ALLOW,
            policy.decide(false, false, false)
        );
    }

    @Test
    void trackedItemFailsClosedWhenSourceTopologyIsUnsupported() {
        assertEquals(
            HopperTransferPolicy.Action.CANCEL,
            policy.decide(true, true, true, false, true)
        );
    }

    @Test
    void eligibleItemFailsClosedWhenDestinationTopologyIsUnsupported() {
        assertEquals(
            HopperTransferPolicy.Action.CANCEL,
            policy.decide(false, false, true, true, false)
        );
    }

    @Test
    void supportedBlockTopologyPreservesExistingTransferDecisions() {
        assertEquals(
            HopperTransferPolicy.Action.ALLOW,
            policy.decide(true, true, true, true, true)
        );
        assertEquals(
            HopperTransferPolicy.Action.CANCEL_AND_SCAN_SOURCE,
            policy.decide(false, false, true, true, true)
        );
    }
}
