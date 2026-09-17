package com.itemguard.custody;

import com.itemguard.data.ItemHistory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces exactly what the live manual server recorded when the owner threw an item out, took it
 * back, and threw it again. The database rows below are copied from that session.
 *
 * <p>The custody count was already correct, but the overview row still displayed raw per-action
 * totals, so the visible number kept climbing with every self drop and re-pick. That is the number a
 * player actually reads, so it is the number that must stop inflating.
 */
class SelfDropSpamRegressionTest {

    private static final UUID OWNER = UUID.fromString("76d9917f-6697-34ef-93a7-cdba078e219f");

    /** Newest-first, as the repository returns. Mirrors the observed rows for code UE8K1J. */
    private static List<ItemHistory> observedWindow() {
        List<ItemHistory> oldestFirst = new ArrayList<>(List.of(
            row("PICKUP", 1789204410220L),
            row("DROP", 1789204418899L),
            row("PICKUP", 1789204420871L)));
        java.util.Collections.reverse(oldestFirst);
        return List.copyOf(oldestFirst);
    }

    private static ItemHistory row(String action, long at) {
        ItemHistory history = new ItemHistory();
        history.setCode("UE8K1J");
        history.setAction(action);
        history.setPlayerName("ThanhRedfield");
        history.setPlayerUuid(OWNER);
        history.setLocation("world (0, 64, 0)");
        history.setTimestamp(at);
        return history;
    }

    @Test void custodyAlreadyReportsNoHandoverForThisExactSession() {
        CustodyChain chain = CustodyChain.replay(observedWindow(), new CustodyTransferPolicy(0L));
        assertEquals(0, chain.transfers());
        assertEquals(1, chain.distinctHolders());
    }

    @Test void theVisibleActivitySummaryMustNotGrowWithSelfDropAndRePick() {
        CustodyActivity activity = CustodyActivity.of(observedWindow(), new CustodyTransferPolicy(0L));
        assertEquals(0, activity.handovers(), "nobody else ever held it");
        assertEquals(1, activity.meaningfulPickups(),
            "taking back your own item is the same hand, so it stays one acquisition");
        assertTrue(activity.selfCycles() >= 1, "the self drop/re-pick loop is still recorded, just not counted");
    }

    @Test void repeatedSpamDoesNotRaiseTheVisibleNumbers() {
        List<ItemHistory> rows = new ArrayList<>();
        long now = 1789204410220L;
        rows.add(row("PICKUP", now));
        for (int i = 0; i < 40; i++) {
            now += 1_000L;
            rows.add(row("DROP", now));
            now += 1_000L;
            rows.add(row("PICKUP", now));
        }
        java.util.Collections.reverse(rows);

        CustodyActivity activity = CustodyActivity.of(List.copyOf(rows), new CustodyTransferPolicy(0L));

        assertEquals(0, activity.handovers());
        assertEquals(1, activity.meaningfulPickups(), "forty self cycles must not look like forty pickups");
        assertEquals(40, activity.selfCycles(), "the raw activity is still visible as its own figure");
    }

    @Test void aRealHandoverStillMovesTheVisibleNumbers() {
        UUID other = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        List<ItemHistory> rows = new ArrayList<>(List.of(
            row("PICKUP", 1000L),
            row("DROP", 2000L),
            row("PICKUP", 3000L)));
        ItemHistory taken = row("PICKUP", 4000L);
        taken.setPlayerUuid(other);
        taken.setPlayerName("Other");
        rows.add(taken);
        java.util.Collections.reverse(rows);

        CustodyActivity activity = CustodyActivity.of(List.copyOf(rows), new CustodyTransferPolicy(0L));

        assertEquals(1, activity.handovers());
        assertEquals(2, activity.meaningfulPickups(), "the new holder taking it is a real acquisition");
    }
}
