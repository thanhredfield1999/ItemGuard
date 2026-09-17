package com.itemguard.custody;

import com.itemguard.tracking.ContainerTransfer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks down which of the named container transfers may move custody.
 *
 * <p>Before this, every chest, hopper and ender chest click was recorded as {@code INVENTORY_MOVE},
 * so the documented rule "taking from a chest counts as a handover" was not actually true, and
 * {@code CONTAINER_TAKE} was allowlisted but never produced by anything.
 */
class ContainerCustodyPolicyTest {

    @Test void takingFromWorldStorageIsTheOnlyContainerTransferThatMovesCustody() {
        assertTrue(CustodyActionPolicy.claimsCustody("CONTAINER_TAKE"),
            "chest hand-off is the main way items change hands without the players meeting");

        assertFalse(CustodyActionPolicy.claimsCustody("CONTAINER_PUT"));
        assertFalse(CustodyActionPolicy.claimsCustody("ENDERCHEST_TAKE"));
        assertFalse(CustodyActionPolicy.claimsCustody("ENDERCHEST_PUT"));
        assertFalse(CustodyActionPolicy.claimsCustody("CARRIED_CONTAINER_TAKE"));
        assertFalse(CustodyActionPolicy.claimsCustody("CARRIED_CONTAINER_PUT"));
        assertFalse(CustodyActionPolicy.claimsCustody("INVENTORY_MOVE"));
    }

    @Test void theEnumAndTheCustodyPolicyAgreeOnEveryTransfer() {
        // Two independent declarations of the same rule must not drift apart.
        for (ContainerTransfer transfer : ContainerTransfer.values()) {
            assertEquals(transfer.claimsCustody(),
                CustodyActionPolicy.claimsCustody(transfer.action()),
                "disagreement for " + transfer.action());
        }
    }

    @Test void anEnderChestRoundTripCannotInflateTheChain() {
        // A player putting an item in their own ender chest and taking it back is one holder.
        assertFalse(CustodyActionPolicy.claimsCustody(ContainerTransfer.ENDERCHEST_PUT.action()));
        assertFalse(CustodyActionPolicy.claimsCustody(ContainerTransfer.ENDERCHEST_TAKE.action()));
    }

    @Test void automatedTransfersHaveNoActionNameAndSoCanNeverClaimCustody() {
        // The hopper path writes no history at all; these are the strings it would have used.
        assertFalse(CustodyActionPolicy.claimsCustody("HOPPER_MOVE"));
        assertFalse(CustodyActionPolicy.claimsCustody("CONTAINER_TRANSFER"));
    }
}
