package com.itemguard.history;

import com.itemguard.data.ItemHistory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Write-boundary defence against history flooding.
 *
 * <p>Threat model: {@code docs/design/2026-09-12-history-write-amplification.md}. A player holding Q
 * writes two permanent rows per second forever, which exhausts storage, buries real evidence outside
 * every bounded query window, and turns an audit feature into a performance cost. Filtering this at
 * render time does not help, because the rows are already on disk.
 *
 * <p>The rule: a repeat of the same action, by the same player, on the same item identity, inside a
 * short window, is the same fact recurring. Record it once and count the repeats.
 */
class HistoryWriteGateTest {

    private static final UUID ITEM = UUID.fromString("75a7db99-0f38-416a-be94-62fce1c33920");
    private static final UUID OTHER_ITEM = UUID.fromString("23263062-21f0-4041-911a-54a68291a2eb");
    private static final UUID PLAYER = UUID.fromString("76d9917f-6697-34ef-93a7-cdba078e219f");
    private static final UUID OTHER_PLAYER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    private static final long WINDOW = 60_000L;

    private static ItemHistory row(String code, UUID item, String action, UUID player, long at) {
        ItemHistory history = new ItemHistory();
        history.setCode(code);
        history.setItemUuid(item);
        history.setAction(action);
        history.setPlayerUuid(player);
        history.setPlayerName("Actor");
        history.setTimestamp(at);
        return history;
    }

    @Test void theFirstEventIsAlwaysWritten() {
        HistoryWriteGate gate = new HistoryWriteGate(WINDOW);
        assertEquals(HistoryWriteDecision.WRITE,
            gate.evaluate(row("11GXH7", ITEM, "PICKUP", PLAYER, 0L)));
    }

    @Test void alternatingDropAndPickupByOneHolderCollapsesToOneRowPerAction() {
        HistoryWriteGate gate = new HistoryWriteGate(WINDOW);
        // Exactly the live sequence: PICKUP, then DROP/PICKUP cycles by the same player.
        assertEquals(HistoryWriteDecision.WRITE, gate.evaluate(row("11GXH7", ITEM, "PICKUP", PLAYER, 0L)));
        assertEquals(HistoryWriteDecision.WRITE, gate.evaluate(row("11GXH7", ITEM, "DROP", PLAYER, 1_000L)));
        assertEquals(HistoryWriteDecision.COALESCE, gate.evaluate(row("11GXH7", ITEM, "PICKUP", PLAYER, 2_000L)));
        assertEquals(HistoryWriteDecision.COALESCE, gate.evaluate(row("11GXH7", ITEM, "DROP", PLAYER, 3_000L)));
        assertEquals(HistoryWriteDecision.COALESCE, gate.evaluate(row("11GXH7", ITEM, "PICKUP", PLAYER, 4_000L)));
    }

    @Test void sustainedSpamNeverGrowsTheRowCount() {
        HistoryWriteGate gate = new HistoryWriteGate(WINDOW);
        int writes = 0;
        long now = 0L;
        for (int i = 0; i < 500; i++) {
            now += 200L;
            if (gate.evaluate(row("11GXH7", ITEM, i % 2 == 0 ? "DROP" : "PICKUP", PLAYER, now))
                == HistoryWriteDecision.WRITE) {
                writes++;
            }
        }
        assertEquals(2, writes, "500 spam events must not become 500 rows; one row per distinct action");
    }

    @Test void aDifferentItemIsTrackedIndependentlySoSpamOnOneDoesNotMaskAnother() {
        HistoryWriteGate gate = new HistoryWriteGate(WINDOW);
        gate.evaluate(row("11GXH7", ITEM, "PICKUP", PLAYER, 0L));
        gate.evaluate(row("11GXH7", ITEM, "DROP", PLAYER, 1_000L));
        assertEquals(HistoryWriteDecision.WRITE,
            gate.evaluate(row("4DQ6XW", OTHER_ITEM, "PICKUP", PLAYER, 1_100L)),
            "a second item's first event is its own fact");
    }

    @Test void anotherPlayerActingOnTheSameItemAlwaysWrites() {
        HistoryWriteGate gate = new HistoryWriteGate(WINDOW);
        gate.evaluate(row("11GXH7", ITEM, "PICKUP", PLAYER, 0L));
        gate.evaluate(row("11GXH7", ITEM, "DROP", PLAYER, 1_000L));
        assertEquals(HistoryWriteDecision.WRITE,
            gate.evaluate(row("11GXH7", ITEM, "PICKUP", OTHER_PLAYER, 2_000L)),
            "a handover is the event this feature exists to record and must never be suppressed");
    }

    @Test void aDifferentActionAlwaysWritesSoRealBehaviourIsNotLost() {
        HistoryWriteGate gate = new HistoryWriteGate(WINDOW);
        gate.evaluate(row("11GXH7", ITEM, "PICKUP", PLAYER, 0L));
        gate.evaluate(row("11GXH7", ITEM, "DROP", PLAYER, 1_000L));
        assertEquals(HistoryWriteDecision.WRITE,
            gate.evaluate(row("11GXH7", ITEM, "CONTAINER_PUT", PLAYER, 2_000L)));
        assertEquals(HistoryWriteDecision.WRITE,
            gate.evaluate(row("11GXH7", ITEM, "DEATH", PLAYER, 3_000L)));
    }

    @Test void afterTheWindowTheSameActionIsANewFactAgain() {
        HistoryWriteGate gate = new HistoryWriteGate(WINDOW);
        gate.evaluate(row("11GXH7", ITEM, "PICKUP", PLAYER, 0L));
        gate.evaluate(row("11GXH7", ITEM, "DROP", PLAYER, 1_000L));
        assertEquals(HistoryWriteDecision.COALESCE,
            gate.evaluate(row("11GXH7", ITEM, "PICKUP", PLAYER, 2_000L)));
        assertEquals(HistoryWriteDecision.WRITE,
            gate.evaluate(row("11GXH7", ITEM, "PICKUP", PLAYER, 2_000L + WINDOW + 1L)),
            "picking the item up an hour later is genuinely new activity");
    }

    @Test void aReturningHandoverIsNeverSuppressedEvenInsideTheWindow() {
        HistoryWriteGate gate = new HistoryWriteGate(WINDOW);
        gate.evaluate(row("11GXH7", ITEM, "PICKUP", PLAYER, 0L));
        gate.evaluate(row("11GXH7", ITEM, "DROP", PLAYER, 500L));
        gate.evaluate(row("11GXH7", ITEM, "PICKUP", OTHER_PLAYER, 1_000L));
        gate.evaluate(row("11GXH7", ITEM, "DROP", OTHER_PLAYER, 1_500L));
        assertEquals(HistoryWriteDecision.WRITE,
            gate.evaluate(row("11GXH7", ITEM, "PICKUP", PLAYER, 2_000L)),
            "custody returning to the first player is still movement between two people");
    }

    @Test void memoryIsBoundedSoTheGateItselfCannotBeUsedForExhaustion() {
        HistoryWriteGate gate = new HistoryWriteGate(WINDOW, 64);
        for (int i = 0; i < 5_000; i++) {
            gate.evaluate(row("CODE" + i, UUID.nameUUIDFromBytes(("item" + i).getBytes()),
                "PICKUP", PLAYER, i * 10L));
        }
        assertTrue(gate.trackedIdentities() <= 64,
            "spamming distinct identities must not grow the gate without bound");
    }

    @Test void rowsWithoutAnIdentityOrActorAreWrittenRatherThanSilentlyDropped() {
        HistoryWriteGate gate = new HistoryWriteGate(WINDOW);
        assertEquals(HistoryWriteDecision.WRITE, gate.evaluate(row("11GXH7", null, "PICKUP", PLAYER, 0L)));
        assertEquals(HistoryWriteDecision.WRITE, gate.evaluate(row("11GXH7", ITEM, "PICKUP", null, 0L)));
        assertEquals(HistoryWriteDecision.WRITE, gate.evaluate(null));
    }

    @Test void negativeWindowIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HistoryWriteGate(-1L));
    }
}
