package com.itemguard.restore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decides when an identity may be declared destroyed.
 *
 * <p>This is the dangerous half of the loss feature: every false positive becomes a free duplicate,
 * because a destroyed identity can be restored. An item inside an unloaded chunk is invisible and
 * perfectly intact, so absence alone must never be enough.
 */
class DestructionConfirmationTest {

    @Test void absentFromACompletedSweepAfterBeingDroppedIsDestroyed() {
        assertTrue(DestructionConfirmation.confirms(
            new DestructionEvidence(true, true, "DROP", true)));
    }

    @Test void absentAfterBeingStoredInAContainerThatIsGoneIsDestroyed() {
        assertTrue(DestructionConfirmation.confirms(
            new DestructionEvidence(true, true, "CONTAINER_PUT", true)));
    }

    @Test void absentAfterBeingStoredInAPlacedShulkerThatIsGoneIsDestroyed() {
        assertTrue(DestructionConfirmation.confirms(
            new DestructionEvidence(true, true, "SHULKER_PUT", true)));
    }

    @Test void absentAfterDyingIsDestroyed() {
        assertTrue(DestructionConfirmation.confirms(
            new DestructionEvidence(true, true, "DEATH", true)));
    }

    @Test void anIncompleteSweepProvesNothing() {
        assertFalse(DestructionConfirmation.confirms(
            new DestructionEvidence(true, false, "DROP", true)),
            "a partial sweep can miss an item that is still there");
    }

    @Test void stillPresentIsNeverDestroyed() {
        assertFalse(DestructionConfirmation.confirms(
            new DestructionEvidence(false, true, "DROP", true)));
    }

    @Test void absentWhileSomeoneWasHoldingItIsNotDestroyed() {
        // Last action says a player took it. Absence then means an unloaded chunk or a logged-out
        // player, not destruction. Declaring this destroyed would be a duplication tap.
        assertFalse(DestructionConfirmation.confirms(
            new DestructionEvidence(true, true, "PICKUP", true)),
            "a held item that cannot be seen is hidden, not gone");
        assertFalse(DestructionConfirmation.confirms(
            new DestructionEvidence(true, true, "CONTAINER_TAKE", true)));
    }

    @Test void absentWithUnloadedChunksInTheSweepIsNotDestroyed() {
        assertFalse(DestructionConfirmation.confirms(
            new DestructionEvidence(true, true, "DROP", false)),
            "if the sweep could not see everywhere, absence is not evidence");
    }

    @Test void anUnknownLastActionRefusesToConfirm() {
        assertFalse(DestructionConfirmation.confirms(
            new DestructionEvidence(true, true, "SOMETHING_NEW", true)),
            "a new action must be classified deliberately, not defaulted into destruction");
        assertFalse(DestructionConfirmation.confirms(
            new DestructionEvidence(true, true, null, true)));
    }

    @Test void nullEvidenceRefusesToConfirm() {
        assertFalse(DestructionConfirmation.confirms(null));
    }

    @Test void releasingActionsAreAnExplicitAllowlist() {
        assertTrue(DestructionConfirmation.isReleasing("DROP"));
        assertTrue(DestructionConfirmation.isReleasing("CONTAINER_PUT"));
        assertTrue(DestructionConfirmation.isReleasing("SHULKER_PUT"));
        assertTrue(DestructionConfirmation.isReleasing("DEATH"));

        assertFalse(DestructionConfirmation.isReleasing("PICKUP"));
        assertFalse(DestructionConfirmation.isReleasing("CONTAINER_TAKE"));
        assertFalse(DestructionConfirmation.isReleasing("ENDERCHEST_PUT"),
            "an ender chest is not reachable by a sweep, so its contents are never 'absent'");
        assertFalse(DestructionConfirmation.isReleasing("INVENTORY_MOVE"));
        assertFalse(DestructionConfirmation.isReleasing("SPAWN"));
    }
}
