package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decides whether an item leaving a player's inventory is unexplained.
 *
 * <p>Matching on the text of {@code /clear} is too weak: aliases exist, other plugins remove items,
 * and {@code Player.performCommand} does not even fire the command event. The robust signal is the
 * disappearance itself — a tracked item that left an inventory with no player action to account for
 * it was removed by something other than the player.
 */
class UnexplainedRemovalPolicyTest {

    private static final UnexplainedRemovalPolicy POLICY = new UnexplainedRemovalPolicy();

    @Test void vanishingWithNoRecordedActionIsUnexplained() {
        assertTrue(POLICY.isUnexplained(null));
    }

    @Test void vanishingAfterTheOwnerDroppedItIsExplained() {
        assertFalse(POLICY.isUnexplained("DROP"));
    }

    @Test void vanishingAfterBeingStoredIsExplained() {
        assertFalse(POLICY.isUnexplained("CONTAINER_PUT"));
        assertFalse(POLICY.isUnexplained("SHULKER_PUT"));
        assertFalse(POLICY.isUnexplained("ENDERCHEST_PUT"));
        assertFalse(POLICY.isUnexplained("CARRIED_CONTAINER_PUT"));
    }

    @Test void vanishingAfterDeathIsExplained() {
        assertFalse(POLICY.isUnexplained("DEATH"));
    }

    @Test void vanishingWhileTheRecordsSayItWasHeldIsUnexplained() {
        // Held one moment, gone the next, with nothing in between: that is a removal.
        assertTrue(POLICY.isUnexplained("PICKUP"));
        assertTrue(POLICY.isUnexplained("CONTAINER_TAKE"));
        assertTrue(POLICY.isUnexplained("INVENTORY_MOVE"));
        assertTrue(POLICY.isUnexplained("SPAWN"));
    }

    @Test void anAlreadyRecordedLossIsNotReportedTwice() {
        assertFalse(POLICY.isUnexplained("CLEARED"));
        assertFalse(POLICY.isUnexplained("BURNED"));
        assertFalse(POLICY.isUnexplained("DESPAWNED"));
        assertFalse(POLICY.isUnexplained("VOID"));
        assertFalse(POLICY.isUnexplained("RESTORED"));
    }

    @Test void caseDoesNotMatter() {
        assertFalse(POLICY.isUnexplained("drop"));
        assertTrue(POLICY.isUnexplained("pickup"));
    }

    @Test void usingAnItemUpIsExplainedRatherThanReportedAsARemoval() {
        assertFalse(POLICY.isUnexplained("USE"));
    }
}
