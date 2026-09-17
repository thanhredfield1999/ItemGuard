package com.itemguard.custody;

import com.itemguard.data.ItemHistory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays recorded history into a custody chain.
 *
 * <p>This is derived from the existing history rows, so it needs no schema change and stays honest:
 * it only ever describes what was recorded, never what "must" have happened in between.
 */
class CustodyChainTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final long MINUTE = 60_000L;

    private static ItemHistory row(String action, UUID player, String name, long at) {
        ItemHistory history = new ItemHistory();
        history.setCode("CODE01");
        history.setItemUuid(UUID.nameUUIDFromBytes("CODE01".getBytes()));
        history.setAction(action);
        history.setPlayerUuid(player);
        history.setPlayerName(name);
        history.setLocation("world (0, 64, 0)");
        history.setTimestamp(at);
        return history;
    }

    /** Rows arrive newest-first from the repository, as the LITE queries return them. */
    private static List<ItemHistory> newestFirst(ItemHistory... rows) {
        List<ItemHistory> list = new ArrayList<>(List.of(rows));
        java.util.Collections.reverse(list);
        return List.copyOf(list);
    }

    @Test void selfDropAndPickupLeavesZeroHandovers() {
        List<ItemHistory> rows = newestFirst(
            row("SPAWN", A, "Alpha", 0),
            row("DROP", A, "Alpha", MINUTE),
            row("PICKUP", A, "Alpha", 2 * MINUTE),
            row("DROP", A, "Alpha", 3 * MINUTE),
            row("PICKUP", A, "Alpha", 4 * MINUTE));

        CustodyChain chain = CustodyChain.replay(rows, new CustodyTransferPolicy(5 * MINUTE));

        assertEquals(0, chain.transfers(), "throwing your own item around is not a handover");
        assertEquals(1, chain.distinctHolders());
        assertEquals(A, chain.currentHolder());
    }

    @Test void handingToAnotherPlayerCountsOnceAndRemembersTheEarlierHolder() {
        List<ItemHistory> rows = newestFirst(
            row("SPAWN", A, "Alpha", 0),
            row("DROP", A, "Alpha", MINUTE),
            row("PICKUP", B, "Bravo", MINUTE + 500));

        CustodyChain chain = CustodyChain.replay(rows, new CustodyTransferPolicy(5 * MINUTE));

        assertEquals(1, chain.transfers());
        assertEquals(2, chain.distinctHolders());
        assertEquals(B, chain.currentHolder());
        assertEquals(List.of(A, B), chain.holders());
    }

    @Test void takingFromAChestCountsAsReceivingButStoringDoesNot() {
        List<ItemHistory> rows = newestFirst(
            row("SPAWN", A, "Alpha", 0),
            row("CONTAINER_PUT", A, "Alpha", MINUTE),
            row("CONTAINER_TAKE", B, "Bravo", 2 * MINUTE));

        CustodyChain chain = CustodyChain.replay(rows, new CustodyTransferPolicy(5 * MINUTE));

        assertEquals(1, chain.transfers());
        assertEquals(B, chain.currentHolder());
    }

    @Test void pingPongInsideTheCooldownDoesNotInflateTheCount() {
        List<ItemHistory> rows = new ArrayList<>();
        rows.add(row("SPAWN", A, "Alpha", 0));
        long now = 0;
        for (int i = 0; i < 10; i++) {
            now += 5_000L;
            rows.add(row("PICKUP", B, "Bravo", now));
            now += 5_000L;
            rows.add(row("PICKUP", A, "Alpha", now));
        }
        java.util.Collections.reverse(rows);

        CustodyChain chain = CustodyChain.replay(List.copyOf(rows), new CustodyTransferPolicy(5 * MINUTE));

        assertEquals(1, chain.transfers(), "two accounts swapping fast must not farm the count");
        assertEquals(2, chain.distinctHolders());
    }

    @Test void anEmptyHistoryIsAnEmptyChainRatherThanAnError() {
        CustodyChain chain = CustodyChain.replay(List.of(), new CustodyTransferPolicy(MINUTE));
        assertEquals(0, chain.transfers());
        assertEquals(0, chain.distinctHolders());
        assertNull(chain.currentHolder());
        assertTrue(chain.holders().isEmpty());
    }

    @Test void rowsWithoutAPlayerUuidAreSkippedInsteadOfCorruptingTheChain() {
        List<ItemHistory> rows = newestFirst(
            row("SPAWN", A, "Alpha", 0),
            row("PICKUP", null, "Unknown", MINUTE),
            row("PICKUP", B, "Bravo", 2 * MINUTE));

        CustodyChain chain = CustodyChain.replay(rows, new CustodyTransferPolicy(5 * MINUTE));

        assertEquals(1, chain.transfers());
        assertEquals(List.of(A, B), chain.holders());
    }

    @Test void theChainIsWindowScopedAndSaysSoRatherThanClaimingLifetimeTruth() {
        List<ItemHistory> rows = newestFirst(
            row("SPAWN", A, "Alpha", 0),
            row("PICKUP", B, "Bravo", MINUTE));

        CustodyChain chain = CustodyChain.replay(rows, new CustodyTransferPolicy(5 * MINUTE));

        assertEquals(2, chain.rowsConsidered());
        assertFalse(chain.holderNames().isEmpty());
        assertEquals("Bravo", chain.holderNames().get(chain.holderNames().size() - 1));
    }
}
