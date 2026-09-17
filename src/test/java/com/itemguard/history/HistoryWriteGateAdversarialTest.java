package com.itemguard.history;

import com.itemguard.data.ItemHistory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial probing of the write gate, written to find what survives the fix rather than to
 * confirm it. Each test states the attack it models.
 */
class HistoryWriteGateAdversarialTest {

    private static final UUID ITEM = UUID.fromString("75a7db99-0f38-416a-be94-62fce1c33920");
    private static final UUID ATTACKER = UUID.fromString("76d9917f-6697-34ef-93a7-cdba078e219f");
    private static final UUID VICTIM = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    private static ItemHistory row(UUID item, String action, UUID player, long at) {
        ItemHistory history = new ItemHistory();
        history.setCode("11GXH7");
        history.setItemUuid(item);
        history.setAction(action);
        history.setPlayerUuid(player);
        history.setPlayerName("Actor");
        history.setTimestamp(at);
        return history;
    }

    private static int writesFor(HistoryWriteGate gate, long totalMillis, long stepMillis) {
        int writes = 0;
        for (long at = 0; at <= totalMillis; at += stepMillis) {
            String action = (at / stepMillis) % 2 == 0 ? "DROP" : "PICKUP";
            if (gate.evaluate(row(ITEM, action, ATTACKER, at)) == HistoryWriteDecision.WRITE) {
                writes++;
            }
        }
        return writes;
    }

    /** Attack: hold the drop key for an hour. Measures the real bound rather than assuming it. */
    @Test void anHourOfMaximumRateSpamIsBoundedByTheWindowNotByKeypresses() {
        HistoryWriteGate gate = new HistoryWriteGate(HistoryWriteGate.DEFAULT_WINDOW_MILLIS);
        long hour = 60L * 60L * 1000L;
        int writes = writesFor(gate, hour, 50L);       // 20 events per second
        int unbounded = (int) (hour / 50L) + 1;

        assertTrue(writes <= 2 * (hour / HistoryWriteGate.DEFAULT_WINDOW_MILLIS) + 4,
            "must be bounded by elapsed windows, got " + writes);
        assertTrue(writes < unbounded / 100,
            "must be at least two orders of magnitude below the unbounded count: "
                + writes + " vs " + unbounded);
    }

    /**
     * Attack: pace the spam to just outside the window so every event escapes the time-based rule.
     * A per-combination cap must still bound it, otherwise a patient attacker buries evidence.
     */
    @Test void pacingSpamJustOutsideTheWindowIsStillBoundedByThePerCombinationCap() {
        HistoryWriteGate gate = new HistoryWriteGate(HistoryWriteGate.DEFAULT_WINDOW_MILLIS);
        long step = HistoryWriteGate.DEFAULT_WINDOW_MILLIS + 1_000L;
        int writes = writesFor(gate, step * 200L, step);

        assertTrue(writes <= 2 * HistoryWriteGate.DEFAULT_MAX_ROWS_PER_COMBINATION,
            "a slow drip must not write one row per event forever, got " + writes);
    }

    /**
     * A full day of patient drip on one item must not be able to push a real handover out of a
     * bounded query window. This is threat T2 from the design note.
     */
    @Test void aDayOfPatientDripCannotFloodPastTheCap() {
        HistoryWriteGate gate = new HistoryWriteGate(HistoryWriteGate.DEFAULT_WINDOW_MILLIS);
        long step = HistoryWriteGate.DEFAULT_WINDOW_MILLIS + 5_000L;
        long day = 24L * 60L * 60L * 1000L;
        int writes = writesFor(gate, day, step);

        assertTrue(writes <= 2 * HistoryWriteGate.DEFAULT_MAX_ROWS_PER_COMBINATION,
            "24h of drip wrote " + writes + " rows; must stay bounded so a handover cannot be buried");
    }

    /**
     * Attack: bury a real handover under spam so it falls outside a bounded query window.
     * The handover must remain among the newest rows.
     */
    @Test void aRealHandoverCannotBeBuriedByLaterSelfSpam() {
        HistoryWriteGate gate = new HistoryWriteGate(HistoryWriteGate.DEFAULT_WINDOW_MILLIS);
        int rowsBefore = 0;
        // The incriminating move: the attacker hands the item to someone else.
        if (gate.evaluate(row(ITEM, "PICKUP", VICTIM, 0L)) == HistoryWriteDecision.WRITE) {
            rowsBefore++;
        }
        // Then the attacker retakes it and spams hard, trying to flush the row above out of view.
        int spamWrites = 0;
        long at = 1_000L;
        if (gate.evaluate(row(ITEM, "PICKUP", ATTACKER, at)) == HistoryWriteDecision.WRITE) {
            spamWrites++;
        }
        for (int i = 0; i < 10_000; i++) {
            at += 50L;
            String action = i % 2 == 0 ? "DROP" : "PICKUP";
            if (gate.evaluate(row(ITEM, action, ATTACKER, at)) == HistoryWriteDecision.WRITE) {
                spamWrites++;
            }
        }
        assertEquals(1, rowsBefore);
        assertTrue(spamWrites < 30,
            "10000 spam events must not add anywhere near 10000 rows, got " + spamWrites);
    }

    /**
     * Attack: pollute someone else's tracked item. Each actor change legitimately writes, so a pair
     * alternating cannot be collapsed. This is inherent and must stay visible.
     */
    @Test void twoActorsAlternatingAlwaysWriteBecauseEachIsAGenuineHandover() {
        HistoryWriteGate gate = new HistoryWriteGate(HistoryWriteGate.DEFAULT_WINDOW_MILLIS);
        int writes = 0;
        for (int i = 0; i < 100; i++) {
            UUID actor = i % 2 == 0 ? ATTACKER : VICTIM;
            if (gate.evaluate(row(ITEM, "PICKUP", actor, i * 100L)) == HistoryWriteDecision.WRITE) {
                writes++;
            }
        }
        assertEquals(100, writes,
            "custody genuinely moves each time, so suppressing these would hide real handovers; "
                + "farming the visible count is handled by the custody pair window instead");
    }

    /** Attack: churn distinct item identities to evict the gate's memory and reset suppression. */
    @Test void evictingTheGateMemoryDoesNotUncapAnItemAlreadyBeingSpammed() {
        HistoryWriteGate gate = new HistoryWriteGate(HistoryWriteGate.DEFAULT_WINDOW_MILLIS, 8);
        gate.evaluate(row(ITEM, "DROP", ATTACKER, 0L));
        assertEquals(HistoryWriteDecision.COALESCE, gate.evaluate(row(ITEM, "DROP", ATTACKER, 100L)));

        // Touch more identities than the cap so the spammed item is evicted.
        for (int i = 0; i < 32; i++) {
            gate.evaluate(row(UUID.nameUUIDFromBytes(("filler" + i).getBytes()), "DROP", ATTACKER, 200L));
        }
        // After eviction the next event writes again: one extra row per eviction cycle, not per event.
        assertEquals(HistoryWriteDecision.WRITE, gate.evaluate(row(ITEM, "DROP", ATTACKER, 300L)));
        assertEquals(HistoryWriteDecision.COALESCE, gate.evaluate(row(ITEM, "DROP", ATTACKER, 400L)),
            "suppression must re-engage immediately, so eviction cannot be farmed per event");
    }

    /** Attack: clock skew or a rewound timestamp to force a write. */
    @Test void aRewoundTimestampWritesOnceAndThenSuppressesAgain() {
        HistoryWriteGate gate = new HistoryWriteGate(HistoryWriteGate.DEFAULT_WINDOW_MILLIS);
        gate.evaluate(row(ITEM, "DROP", ATTACKER, 10_000L));
        assertEquals(HistoryWriteDecision.WRITE, gate.evaluate(row(ITEM, "DROP", ATTACKER, 1_000L)),
            "an out-of-order event is treated as new rather than silently dropped");
        assertEquals(HistoryWriteDecision.COALESCE, gate.evaluate(row(ITEM, "DROP", ATTACKER, 1_500L)));
    }
}
