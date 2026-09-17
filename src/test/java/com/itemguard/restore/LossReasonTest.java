package com.itemguard.restore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Names the reason an item stopped existing.
 *
 * <p>"Absent from a sweep" is not a reason an admin can act on. Burned in lava, removed by a clear
 * command, and despawned off the ground are different events with different blame, and a restore
 * decision needs to know which one happened.
 */
class LossReasonTest {

    @Test void fireAndLavaAreRecordedAsBurned() {
        assertEquals(LossReason.BURNED, LossReason.fromCause("FIRE"));
        assertEquals(LossReason.BURNED, LossReason.fromCause("FIRE_TICK"));
        assertEquals(LossReason.BURNED, LossReason.fromCause("LAVA"));
        assertEquals(LossReason.BURNED, LossReason.fromCause("BURNED"));
    }

    @Test void commandRemovalIsRecordedAsCleared() {
        assertEquals(LossReason.CLEARED, LossReason.fromCause("CLEARED"));
        assertEquals(LossReason.CLEARED, LossReason.fromCause("PLUGIN"));
        assertEquals(LossReason.CLEARED, LossReason.fromCause("COMMAND"));
    }

    @Test void theGroundTimerIsRecordedAsDespawned() {
        assertEquals(LossReason.DESPAWNED, LossReason.fromCause("DESPAWN"));
        assertEquals(LossReason.DESPAWNED, LossReason.fromCause("DESPAWNED"));
    }

    @Test void theVoidIsItsOwnReason() {
        assertEquals(LossReason.VOID, LossReason.fromCause("VOID"));
    }

    @Test void anythingElseStaysUnknownRatherThanBeingGuessed() {
        // An admin must never read an invented reason as fact.
        assertEquals(LossReason.UNKNOWN, LossReason.fromCause("SOMETHING_NEW"));
        assertEquals(LossReason.UNKNOWN, LossReason.fromCause(null));
        assertEquals(LossReason.UNKNOWN, LossReason.fromCause(""));
    }

    @Test void eachReasonWritesItsOwnHistoryAction() {
        assertEquals("BURNED", LossReason.BURNED.action());
        assertEquals("CLEARED", LossReason.CLEARED.action());
        assertEquals("DESPAWNED", LossReason.DESPAWNED.action());
        assertEquals("VOID", LossReason.VOID.action());
        assertEquals("LOST", LossReason.UNKNOWN.action());
    }

    @Test void everyReasonExplainsItselfInBothLanguages() {
        for (LossReason reason : LossReason.values()) {
            assertFalse(reason.describe(false).isBlank(), reason.name() + " english");
            assertFalse(reason.describe(true).isBlank(), reason.name() + " vietnamese");
        }
    }

    @Test void onlyAKnownReasonCountsAsConfirmedDestruction() {
        // An unknown reason must not unlock a restore: that is how false positives become duplicates.
        assertTrue(LossReason.BURNED.confirmsDestruction());
        assertTrue(LossReason.CLEARED.confirmsDestruction());
        assertTrue(LossReason.DESPAWNED.confirmsDestruction());
        assertTrue(LossReason.VOID.confirmsDestruction());
        assertFalse(LossReason.UNKNOWN.confirmsDestruction());
    }

    @Test void caseDoesNotMatter() {
        assertEquals(LossReason.BURNED, LossReason.fromCause("lava"));
        assertEquals(LossReason.CLEARED, LossReason.fromCause("  command  "));
    }
}
