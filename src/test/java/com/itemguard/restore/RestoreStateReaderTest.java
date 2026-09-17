package com.itemguard.restore;

import com.itemguard.data.ItemHistory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reads an identity's restore-relevant state out of its history.
 *
 * <p>No schema change: the {@code RESTORED} row is itself the record that a restore happened, and
 * the loss rows are the record that it was destroyed. Deriving the state from history means the
 * audit trail and the gate can never disagree.
 */
class RestoreStateReaderTest {

    private static final UUID ITEM = UUID.fromString("75a7db99-0f38-416a-be94-62fce1c33920");

    private static ItemHistory row(String action, long at) {
        ItemHistory history = new ItemHistory();
        history.setCode("11GXH7");
        history.setItemUuid(ITEM);
        history.setAction(action);
        history.setPlayerName("Actor");
        history.setPlayerUuid(UUID.randomUUID());
        history.setTimestamp(at);
        return history;
    }

    /** Newest first, as the repository returns. */
    private static List<ItemHistory> newestFirst(String... actions) {
        java.util.List<ItemHistory> rows = new java.util.ArrayList<>();
        long at = actions.length * 1000L;
        for (String action : actions) {
            rows.add(row(action, at));
            at -= 1000L;
        }
        return List.copyOf(rows);
    }

    @Test void aBurnedItemIsDestroyedWithThatReason() {
        RestoreStateReader.State state = RestoreStateReader.read(newestFirst("BURNED", "DROP", "PICKUP"));
        assertEquals(IdentityState.DESTROYED, state.identityState());
        assertEquals(LossReason.BURNED, state.lossReason());
        assertFalse(state.alreadyRestored());
    }

    @Test void aClearedItemIsDestroyedWithThatReason() {
        RestoreStateReader.State state = RestoreStateReader.read(newestFirst("CLEARED", "PICKUP"));
        assertEquals(IdentityState.DESTROYED, state.identityState());
        assertEquals(LossReason.CLEARED, state.lossReason());
    }

    @Test void anItemStillBeingCarriedIsHeld() {
        RestoreStateReader.State state = RestoreStateReader.read(newestFirst("PICKUP", "DROP"));
        assertEquals(IdentityState.HELD, state.identityState());
        assertEquals(LossReason.UNKNOWN, state.lossReason());
    }

    @Test void anItemOnTheGroundIsDropped() {
        assertEquals(IdentityState.DROPPED,
            RestoreStateReader.read(newestFirst("DROP", "PICKUP")).identityState());
    }

    @Test void anItemInAChestIsHeldByThatChestNotDestroyed() {
        // Stored is not destroyed: restoring it would duplicate what is sitting in the chest.
        assertEquals(IdentityState.HELD,
            RestoreStateReader.read(newestFirst("CONTAINER_PUT", "PICKUP")).identityState());
    }

    @Test void aPreviousRestoreIsRememberedForever() {
        // The item was restored, then lost again. One restore per identity, ever.
        RestoreStateReader.State state =
            RestoreStateReader.read(newestFirst("CLEARED", "PICKUP", "RESTORED", "BURNED"));
        assertEquals(IdentityState.DESTROYED, state.identityState());
        assertTrue(state.alreadyRestored());
    }

    @Test void aRestoredItemCurrentlyHeldIsHeldAndAlreadyRestored() {
        RestoreStateReader.State state = RestoreStateReader.read(newestFirst("RESTORED", "CLEARED"));
        assertEquals(IdentityState.HELD, state.identityState());
        assertTrue(state.alreadyRestored());
    }

    @Test void anEmptyHistoryIsUnknownAndRefusesEverything() {
        RestoreStateReader.State state = RestoreStateReader.read(List.of());
        assertEquals(IdentityState.UNKNOWN, state.identityState());
        assertFalse(state.alreadyRestored());
    }

    @Test void nullHistoryIsUnknownRatherThanThrowing() {
        assertEquals(IdentityState.UNKNOWN, RestoreStateReader.read(null).identityState());
    }

    @Test void anUnrecognisedLatestActionIsUnknownNotGuessedAsDestroyed() {
        // Fail-closed: a new action must be classified before it can unlock a restore.
        assertEquals(IdentityState.UNKNOWN,
            RestoreStateReader.read(newestFirst("SOMETHING_NEW")).identityState());
    }
}
