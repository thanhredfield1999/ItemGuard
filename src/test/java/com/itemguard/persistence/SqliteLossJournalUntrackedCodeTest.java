package com.itemguard.persistence;

import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import com.itemguard.listeners.ItemLossPolicy;
import com.itemguard.listeners.LossJournal;
import com.itemguard.listeners.LossRecord;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pruned {@code tracked_items} row is a permanent answer, and false is not how to say it.
 *
 * <p>The PDC tag lives on the item and outlives the row. Once the row is gone {@code markCleared}
 * updates nothing, {@code recordLoss} answers false, and false means "refused, try again" to the
 * coordinator — which rearms the baseline, so the next scan submits the same code, gets the same
 * false, and rearms again. Nothing is written and nothing stops: a bounded livelock for as long as
 * the tag exists.
 *
 * <p>The answer has to be distinguishable from a refusal without becoming a claim that a loss was
 * recorded: there is no row to clear, so there is nothing to record and nothing to retry.
 */
class SqliteLossJournalUntrackedCodeTest {

    private static final String CODE = "AB12CD";
    private static final long AWAIT_SECONDS = 10L;

    @TempDir
    Path tempDir;

    private final AtomicLong clock = new AtomicLong(9_000L);
    private final UUID itemUuid = UUID.randomUUID();
    private final UUID playerUuid = UUID.randomUUID();
    private final List<Throwable> failures = new ArrayList<>();

    @Test
    void aCodeWhoseTrackedRowWasPrunedIsReportedAsUntrackedRatherThanRefused() throws Exception {
        try (SqliteConnectionOwner owner = openOwner("pruned.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            // History survives, the summary row does not: exactly what pruning leaves behind, and
            // exactly what a stack still carrying its PDC tag looks like afterwards.
            repository.logHistory(history("PICKUP", 1_000L));
            owner.flush();
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);

            ExecutionException raised = assertThrows(
                ExecutionException.class,
                () -> journal.recordLoss(loss(1L)).get(AWAIT_SECONDS, TimeUnit.SECONDS),
                "an untracked code still answered as an ordinary refusal"
            );
            assertInstanceOf(
                LossJournal.UntrackedCodeException.class,
                raised.getCause(),
                "the untracked answer is not distinguishable from a real failure"
            );
        }
    }

    @Test
    void anUntrackedCodeWritesNoOrphanHistoryRow() throws Exception {
        try (SqliteConnectionOwner owner = openOwner("pruned-no-orphan.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.logHistory(history("PICKUP", 1_000L));
            owner.flush();
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);

            assertThrows(
                ExecutionException.class,
                () -> journal.recordLoss(loss(2L)).get(AWAIT_SECONDS, TimeUnit.SECONDS)
            );
            owner.flush();

            assertTrue(
                repository.getHistory(CODE, 10).stream()
                    .noneMatch(row -> "CLEARED".equals(row.getAction())),
                "a code with no tracked row must not gain a loss row behind it"
            );
        }
    }

    @Test
    void aTrackedCodeStillRecordsItsLossNormally() throws Exception {
        // The control that keeps the fix narrow: only the pruned case changes answer.
        try (SqliteConnectionOwner owner = openOwner("tracked.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(item());
            repository.updateLastAction(
                CODE, "PICKUP", "world (0, 64, 0)", "Thanh", playerUuid, 1_000L);
            repository.logHistory(history("PICKUP", 1_000L));
            owner.flush();
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);

            assertTrue(
                journal.recordLoss(loss(3L)).get(AWAIT_SECONDS, TimeUnit.SECONDS),
                "a genuine loss on a tracked code must still be written"
            );
            owner.flush();
            assertEquals(
                "CLEARED",
                repository.getItem(CODE).orElseThrow().getLastAction(),
                "the tracked summary must still move to CLEARED"
            );
        }
    }

    private SqliteConnectionOwner openOwner(String fileName) {
        return new SqliteConnectionOwner(tempDir.resolve(fileName), failures::add);
    }

    private ItemData item() {
        ItemData item = new ItemData(CODE, itemUuid);
        item.setOwnerUuid(playerUuid);
        item.setOwnerName("Thanh");
        item.setMaterial(Material.DIAMOND_SWORD);
        return item;
    }

    private ItemHistory history(String action, long timestamp) {
        ItemHistory history = new ItemHistory(
            CODE, itemUuid, action, "Thanh", playerUuid, "world (0, 64, 0)"
        );
        history.setTimestamp(timestamp);
        return history;
    }

    /** No coordinator behind a journal test, so the permit is always there to take. */
    private LossRecord loss(long generation) {
        return new LossRecord(
            CODE,
            itemUuid,
            ItemLossPolicy.forInventoryRemoval(),
            playerUuid,
            generation,
            new UUID(0L, generation),
            () -> true,
            "world (0, 64, 0)"
        );
    }
}
