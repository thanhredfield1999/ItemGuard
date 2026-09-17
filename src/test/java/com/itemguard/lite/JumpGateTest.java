package com.itemguard.lite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decides whether left-clicking a timeline row may teleport the viewer to that container.
 *
 * <p>Specification: {@code docs/design/2026-09-12-timeline-icons-and-jump.md}. A chest position is
 * exactly what a raider wants, so this is staff-only and fail-closed. Every refusal is a separate
 * test because a single missed branch turns an audit tool into a base finder.
 */
class JumpGateTest {

    private static JumpRequest staffJumpToChest() {
        return new JumpRequest("CONTAINER_TAKE", true, true, true, true, true);
    }

    @Test void staffMayJumpToAContainerRowTheyCanSee() {
        JumpDecision decision = JumpGate.evaluate(staffJumpToChest());
        assertTrue(decision.allowed());
        assertEquals(JumpRefusal.NONE, decision.refusal());
    }

    @Test void aPlayerWithoutPermissionIsRefusedFirstOfAll() {
        // Checked before anything else so a refusal cannot be used to probe for chest positions.
        JumpDecision decision = JumpGate.evaluate(
            new JumpRequest("CONTAINER_TAKE", true, true, true, true, false));
        assertFalse(decision.allowed());
        assertEquals(JumpRefusal.NO_PERMISSION, decision.refusal());
    }

    @Test void rowsWhosePositionIsAPlayerAreNotAJumpTarget() {
        // Where somebody stood once is not a place to go, and it would leak their movements.
        for (String action : new String[]{"PICKUP", "DROP", "DEATH", "INVENTORY_MOVE",
                                         "ENDERCHEST_TAKE", "ENDERCHEST_PUT",
                                         "CARRIED_CONTAINER_TAKE"}) {
            JumpDecision decision = JumpGate.evaluate(
                new JumpRequest(action, true, true, true, true, true));
            assertFalse(decision.allowed(), action + " must not be a jump target");
            assertEquals(JumpRefusal.NOT_A_CONTAINER, decision.refusal(), action);
        }
    }

    @Test void bothContainerActionsAreJumpTargets() {
        assertTrue(JumpGate.evaluate(
            new JumpRequest("CONTAINER_TAKE", true, true, true, true, true)).allowed());
        assertTrue(JumpGate.evaluate(
            new JumpRequest("CONTAINER_PUT", true, true, true, true, true)).allowed());
    }

    @Test void aRowWithNoRecordedPositionIsRefused() {
        JumpDecision decision = JumpGate.evaluate(
            new JumpRequest("CONTAINER_TAKE", false, true, true, true, true));
        assertFalse(decision.allowed());
        assertEquals(JumpRefusal.NO_POSITION, decision.refusal());
    }

    @Test void anUnloadedWorldIsRefusedRatherThanLoadedOnDemand() {
        JumpDecision decision = JumpGate.evaluate(
            new JumpRequest("CONTAINER_TAKE", true, false, true, true, true));
        assertFalse(decision.allowed());
        assertEquals(JumpRefusal.WORLD_NOT_LOADED, decision.refusal());
    }

    @Test void aPositionWithNoSafeStandingSpotIsRefusedNotAttempted() {
        // A chest can be walled in, or the terrain may have changed since the row was written.
        JumpDecision decision = JumpGate.evaluate(
            new JumpRequest("CONTAINER_TAKE", true, true, false, true, true));
        assertFalse(decision.allowed());
        assertEquals(JumpRefusal.NO_SAFE_SPOT, decision.refusal());
    }

    @Test void aRedactedRowIsRefusedEvenForStaffFlaggedRequests() {
        // Defence in depth: if the row was hidden from this viewer, its position is hidden too.
        JumpDecision decision = JumpGate.evaluate(
            new JumpRequest("CONTAINER_TAKE", true, true, true, false, true));
        assertFalse(decision.allowed());
        assertEquals(JumpRefusal.ROW_REDACTED, decision.refusal());
    }

    @Test void aNullRequestIsRefused() {
        JumpDecision decision = JumpGate.evaluate(null);
        assertFalse(decision.allowed());
        assertEquals(JumpRefusal.NO_POSITION, decision.refusal());
    }

    @Test void everyRefusalExplainsItselfToTheOperator() {
        for (JumpRefusal refusal : JumpRefusal.values()) {
            assertNotNull(refusal.message());
            assertFalse(refusal.message().isBlank(), refusal.name());
        }
    }
}
