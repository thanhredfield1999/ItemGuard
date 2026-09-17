package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for a false "removed by /clear" row, found by running two real clients.
 *
 * <p>Observed sequence for one tracked sword: {@code SPAWN, DEATH, CLEARED, PICKUP}. The sword
 * dropped on death and another player picked it up, so the {@code CLEARED} row is wrong.
 *
 * <p>This matters beyond a cosmetic mislabel: {@code CLEARED} confirms destruction, and a destroyed
 * identity can be restored. A false loss row for an item lying on the ground is a duplication path.
 *
 * <p>Root cause: the inventory watcher asked {@code tracked_items.last_action}, which the death path
 * never updated, so the watcher saw a holding action and called the departure unexplained. The
 * newest history row is the authoritative record.
 */
class DeathIsNotAClearRegressionTest {

    private static final UnexplainedRemovalPolicy POLICY = new UnexplainedRemovalPolicy();

    @Test void aDeathExplainsTheItemLeavingTheInventory() {
        assertFalse(POLICY.isUnexplained("DEATH"),
            "the item dropped with the body; it was not removed by a command");
    }

    @Test void theExactObservedSequenceMustNotProduceAClear() {
        // The watcher's question is "what was the last recorded action when the item vanished?".
        // With DEATH as that answer, no loss row may be written.
        assertFalse(POLICY.isUnexplained("DEATH"));
    }

    @Test void aGenuineClearIsStillReported() {
        // The fix must not silence the real case: held one tick, gone the next, nothing in between.
        assertTrue(POLICY.isUnexplained("PICKUP"));
        assertTrue(POLICY.isUnexplained("CONTAINER_TAKE"));
        assertTrue(POLICY.isUnexplained("SPAWN"));
    }

    @Test void everyReleasingActionExplainsItself() {
        for (String action : new String[]{"DROP", "DEATH", "CONTAINER_PUT", "ENDERCHEST_PUT",
                                          "CARRIED_CONTAINER_PUT", "USE"}) {
            assertFalse(POLICY.isUnexplained(action), action + " must not look like a removal");
        }
    }
}
