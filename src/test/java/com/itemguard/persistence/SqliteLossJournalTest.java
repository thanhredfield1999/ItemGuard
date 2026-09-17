package com.itemguard.persistence;

import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import com.itemguard.dupe.ScanEpochGenerator;
import com.itemguard.listeners.ItemLossPolicy;
import com.itemguard.listeners.LossRecord;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The concrete loss journal against a real SQLite file.
 *
 * <p>Every test here is about a property the coordinator cannot check for itself: it hands a record
 * over a thread boundary and only learns "written", "refused" or "failed". What those three answers
 * are allowed to mean is decided in this class.
 */
class SqliteLossJournalTest {

    private static final String CODE = "AB12CD";
    private static final String DATABASE_THREAD = "ItemGuard-DB";
    private static final long AWAIT_SECONDS = 10L;

    @TempDir
    Path tempDir;

    private final AtomicLong clock = new AtomicLong(9_000L);
    private final UUID itemUuid = UUID.randomUUID();
    private final UUID playerUuid = UUID.randomUUID();
    private final List<Throwable> failures = new ArrayList<>();

    /** Every latch parking a database worker, so teardown can release one a failed test did not. */
    private final List<CountDownLatch> gates = new ArrayList<>();

    /**
     * The read must leave the calling thread immediately and answer from the database thread.
     *
     * <p>The scan calls this from a tick. A journal that reads on the caller's thread is the bug the
     * whole seam exists to remove, and it hides well: with a warm page cache the synchronous version
     * returns fast enough to look asynchronous. Holding the executor makes the difference visible —
     * a future that is already done by the time the database thread is blocked was never queued.
     */
    @Test
    void readAndWriteAreQueuedOnTheSerialDatabaseThread() throws Exception {
        try (SqliteConnectionOwner owner = openOwner("serial.db");
             Cleanup released = () -> releaseBlockedWorkers(owner)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            trackItemWithHistory(owner, repository, "PICKUP", 1_000L);
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);

            AtomicReference<String> readThread = new AtomicReference<>();
            CountDownLatch readCallback = new CountDownLatch(1);
            CountDownLatch readGate = blockDatabaseThread(owner);
            CompletableFuture<String> read = journal.lastRecordedAction(CODE);
            read.whenComplete((action, failure) -> {
                readThread.set(Thread.currentThread().getName());
                readCallback.countDown();
            });
            assertFalse(read.isDone(), "read ran on the calling thread");
            readGate.countDown();

            assertEquals("PICKUP", read.get(AWAIT_SECONDS, TimeUnit.SECONDS));
            // `get()` returning does not mean the dependent action has run: the result becomes visible
            // before the callbacks are executed, so asserting straight after `get()` is a race, and
            // this test failed once under load for that reason alone. Waiting for the callback keeps
            // exactly the assertion that matters - which thread answered.
            assertTrue(readCallback.await(AWAIT_SECONDS, TimeUnit.SECONDS), "read callback never ran");
            assertEquals(DATABASE_THREAD, readThread.get());
            assertNotEquals(Thread.currentThread().getName(), readThread.get());

            AtomicReference<String> writeThread = new AtomicReference<>();
            CountDownLatch writeCallback = new CountDownLatch(1);
            CountDownLatch writeGate = blockDatabaseThread(owner);
            CompletableFuture<Boolean> write = journal.recordLoss(loss(1L));
            write.whenComplete((recorded, failure) -> {
                writeThread.set(Thread.currentThread().getName());
                writeCallback.countDown();
            });
            assertFalse(write.isDone(), "write ran on the calling thread");
            writeGate.countDown();

            assertTrue(write.get(AWAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(writeCallback.await(AWAIT_SECONDS, TimeUnit.SECONDS), "write callback never ran");
            assertEquals(DATABASE_THREAD, writeThread.get());
            assertNotEquals(Thread.currentThread().getName(), writeThread.get());
        }
    }

    /**
     * The summary update and the history row are one transaction or they are nothing.
     *
     * <p>A record with no item UUID cannot produce a history row — the column is {@code NOT NULL} —
     * so the write fails after the summary has already been updated. If that half survives, the
     * database claims the item was cleared with no history row behind the claim, and a restore
     * decision would be made on evidence that does not exist.
     */
    @Test
    void aFailedHistoryInsertRollsBackTheActionUpdate() {
        try (SqliteConnectionOwner owner = openOwner("rollback.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            trackItemWithHistory(owner, repository, "PICKUP", 1_000L);
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);

            CompletableFuture<Boolean> write = journal.recordLoss(
                new LossRecord(
                    CODE,
                    null,
                    ItemLossPolicy.forInventoryRemoval(),
                    playerUuid,
                    1L,
                    UUID.randomUUID(),
                    () -> true,
                    "world (0, 64, 0)"
                )
            );

            assertThrows(
                ExecutionException.class,
                () -> write.get(AWAIT_SECONDS, TimeUnit.SECONDS),
                "a write that cannot be applied must not complete normally"
            );
            // Reported through the owner, which is the proof the statement reached the transaction
            // rather than being screened out before it: a pre-flight rejection rolls nothing back.
            assertFalse(failures.isEmpty(), "the database failure was never reported");
            ItemData item = repository.getItem(CODE).orElseThrow();
            assertEquals("PICKUP", item.getLastAction());
            assertEquals(1, repository.getHistoryCount(CODE));
        }
    }

    /**
     * The same departure submitted twice is one loss.
     *
     * <p>Retries are ordinary here: a dispatch back to the main thread can be refused after the write
     * was already committed, and the next pass re-submits. Two rows for one departure would double
     * every loss statistic and make the history read ambiguous about how often the item was actually
     * cleared.
     */
    @Test
    void replayingOneDepartureAppendsASingleRow() throws Exception {
        try (SqliteConnectionOwner owner = openOwner("replay.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            trackItemWithHistory(owner, repository, "PICKUP", 1_000L);
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);

            assertTrue(journal.recordLoss(loss(7L)).get(AWAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(
                journal.recordLoss(loss(7L)).get(AWAIT_SECONDS, TimeUnit.SECONDS),
                "a replay of a recorded departure is a success, not a refusal"
            );

            assertEquals(2, repository.getHistoryCount(CODE));
            assertEquals(1, countAction(repository, "CLEARED"));
            ItemData item = repository.getItem(CODE).orElseThrow();
            assertEquals("CLEARED", item.getLastAction());
            // Off the main thread there is no trustworthy position, so the last known one stands.
            assertEquals("world (0, 64, 0)", item.getLastLocation());
        }
    }

    /**
     * Idempotency is per departure, not "a loss is already the newest action".
     *
     * <p>An item that was lost, came back and was lost again produced two separate events, and only
     * the second one explains where it is now. Deduplicating on the latest action instead of on the
     * departure silently drops that second event — the code stops being reported as lost even though
     * it is.
     */
    @Test
    void aLaterDepartureOfTheSameCodeIsRecordedAgain() throws Exception {
        try (SqliteConnectionOwner owner = openOwner("second-departure.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            trackItemWithHistory(owner, repository, "PICKUP", 1_000L);
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);

            assertTrue(journal.recordLoss(loss(7L)).get(AWAIT_SECONDS, TimeUnit.SECONDS));

            // The item turned up again and was picked up, which is what makes the next disappearance
            // a new departure rather than a repeat of the old one.
            repository.logHistory(history("PICKUP", 12_000L));
            repository.updateLastAction(CODE, "PICKUP", "world (0, 64, 0)", "Thanh", playerUuid, 12_000L);
            clock.set(13_000L);

            assertTrue(journal.recordLoss(loss(8L)).get(AWAIT_SECONDS, TimeUnit.SECONDS));

            assertEquals(4, repository.getHistoryCount(CODE));
            assertEquals(2, countAction(repository, "CLEARED"));
            assertEquals("CLEARED", repository.getItem(CODE).orElseThrow().getLastAction());
        }
    }

    /**
     * The fence is re-checked by the worker that writes, not by the caller that submits.
     *
     * <p>A drop is queued ahead of the loss while the database thread is held. At the moment
     * {@code recordLoss} is called the committed state still says the departure is unexplained; by
     * the time the write runs, the drop has landed and explains it. A journal that judged at
     * submission time writes a false {@code CLEARED} for an item the player put down on purpose.
     */
    @Test
    void theFenceIsEvaluatedWhenTheWriteActuallyRuns() throws Exception {
        try (SqliteConnectionOwner owner = openOwner("fence.db");
             Cleanup released = () -> releaseBlockedWorkers(owner)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            trackItemWithHistory(owner, repository, "PICKUP", 1_000L);
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);

            CountDownLatch gate = blockDatabaseThread(owner);
            repository.logHistory(history("DROP", 3_000L));
            CompletableFuture<Boolean> write = journal.recordLoss(loss(7L));
            assertFalse(write.isDone());
            gate.countDown();

            assertFalse(
                write.get(AWAIT_SECONDS, TimeUnit.SECONDS),
                "the departure was explained before the write ran"
            );
            assertEquals(2, repository.getHistoryCount(CODE));
            assertEquals(0, countAction(repository, "CLEARED"));
            assertEquals("PICKUP", repository.getItem(CODE).orElseThrow().getLastAction());
            assertTrue(failures.isEmpty(), "a refusal is a decision, not a failure");
        }
    }

    /**
     * Two actions in the same millisecond still have one newest.
     *
     * <p>Timestamps collide in practice — a click and the scan pass that follows it land inside one
     * millisecond often enough. Ordering on the timestamp alone leaves the answer to whatever order
     * SQLite returns rows in, so the same database can call one code explained and unexplained on
     * consecutive reads. The insertion order decides it, and it decides it the same way every time.
     */
    @Test
    void aTimestampTieResolvesToTheRowWrittenLast() throws Exception {
        try (SqliteConnectionOwner owner = openOwner("tie.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            trackItemWithHistory(owner, repository, "PICKUP", 5_000L);
            repository.logHistory(history("PICKUP", 5_000L));
            repository.logHistory(history("DROP", 5_000L));
            owner.flush();
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);

            for (int attempt = 0; attempt < 5; attempt++) {
                assertEquals(
                    "DROP",
                    journal.lastRecordedAction(CODE).get(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "tie-break is not deterministic"
                );
            }

            // And the write agrees with the read: the drop explains the departure, so no loss.
            assertFalse(journal.recordLoss(loss(7L)).get(AWAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(0, countAction(repository, "CLEARED"));
        }
    }

    /**
     * No history is not the same answer as no item.
     *
     * <p>A tracked code that has never been through the history table still has a summary action, and
     * an untracked code has nothing at all. Both must answer normally: an exception here means "the
     * read failed", which rearms a baseline instead of retiring it.
     */
    @Test
    void codesWithoutHistoryFallBackToTheStoredSummary() throws Exception {
        try (SqliteConnectionOwner owner = openOwner("fallback.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(item());
            owner.flush();
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);

            assertEquals("SPAWN", journal.lastRecordedAction(CODE).get(AWAIT_SECONDS, TimeUnit.SECONDS));
            assertNull(journal.lastRecordedAction("ZZ99ZZ").get(AWAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(failures.isEmpty());
        }
    }

    /**
     * A departure that is genuinely new must be recorded even when its generation is not.
     *
     * <p>{@link ScanEpochGenerator#next()} is {@code max(clock, previous + 1)}. Several passes inside
     * one millisecond therefore run <em>ahead</em> of the wall clock, and that lead lives only in the
     * instance — a generator built fresh after a restart starts again from the clock. So an epoch the
     * old instance had already issued can be issued a second time by the new one. No clock step
     * backwards is needed, only a restart while the epochs were ahead of the clock.
     *
     * <p>When that happens, both halves of a generation-based departure key repeat: the epoch, and the
     * item UUID the database preserved across the restart. A journal keyed that way finds the old row,
     * answers {@code true} and writes nothing — so the coordinator retires the baseline believing a
     * loss is on record while the summary still says the item was picked up, and the second loss is
     * never written at all. Under-reporting a loss is the one failure this whole path exists to avoid.
     *
     * <p>The item is picked up again between the two losses, so this is not a retry of the first
     * departure under any reading: it is a second disappearance with its own evidence. Suppressing it
     * is wrong whatever the identity scheme, which is why this test states the requirement rather than
     * the current behaviour. The replay guarantee it must not break is
     * {@link #replayingOneDepartureAppendsASingleRow()}.
     */
    @Test
    void aNewDepartureIsRecordedAfterRestartEvenWhenTheGenerationRepeats() throws Exception {
        AtomicLong scanClock = new AtomicLong(10_000L);
        ScanEpochGenerator beforeRestart = new ScanEpochGenerator(scanClock::get);
        beforeRestart.next();
        long reusedEpoch = beforeRestart.next();
        assertEquals(10_001L, reusedEpoch, "epochs no longer outrun the clock within a pass");

        // Two disappearances that share a generation. The baseline saw the item in between, so these
        // are two events by every measure except the epoch they happened to draw.
        UUID departureBeforeRestart = UUID.randomUUID();
        UUID departureAfterRestart = UUID.randomUUID();

        try (SqliteConnectionOwner firstRun = openOwner("restart-collision.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(firstRun);
            trackItemWithHistory(firstRun, repository, "PICKUP", 1_000L);

            assertTrue(new SqliteLossJournal(firstRun, clock::get)
                .recordLoss(loss(reusedEpoch, departureBeforeRestart))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, countAction(repository, "CLEARED"));
        }

        // The restart took a moment, so the clock has caught up with the epoch already issued.
        scanClock.set(10_001L);

        try (SqliteConnectionOwner secondRun = openOwner("restart-collision.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(secondRun);
            ScanEpochGenerator afterRestart = new ScanEpochGenerator(scanClock::get);
            assertEquals(
                reusedEpoch,
                afterRestart.next(),
                "a fresh generator no longer reuses an epoch; re-derive this regression"
            );

            // The item came back and was picked up, which makes the next disappearance a new event.
            repository.logHistory(history("PICKUP", 20_000L));
            repository.updateLastAction(CODE, "PICKUP", "world (0, 64, 0)", "Thanh", playerUuid, 20_000L);
            secondRun.flush();
            clock.set(21_000L);

            assertTrue(
                new SqliteLossJournal(secondRun, clock::get)
                    .recordLoss(loss(reusedEpoch, departureAfterRestart))
                    .get(AWAIT_SECONDS, TimeUnit.SECONDS),
                "the second loss was refused"
            );
            assertEquals(
                2,
                countAction(repository, "CLEARED"),
                "the new departure was suppressed as a replay of the pre-restart one"
            );
            assertEquals(
                "CLEARED",
                repository.getHistory(CODE, 1).getFirst().getAction(),
                "the newest row is not the loss just recorded"
            );
            assertEquals("CLEARED", repository.getItem(CODE).orElseThrow().getLastAction());
        }
    }

    /**
     * The residual window the token exists for: a write that committed and then lost its answer.
     *
     * <p>The coordinator rearms on an ambiguous outcome and the next pass resubmits the same
     * disappearance. Usually the action fence alone stops the second write, because the first one left
     * {@code CLEARED} as the newest action. Not always: anything that appends a non-explaining action
     * after the commit — a stale listener, a restore — reopens the fence, and then only the token says
     * these two submissions are one loss. The {@code PICKUP} appended here is that interleaving.
     */
    @Test
    void anAmbiguousWriteResubmittedWithTheSameTokenStaysOneLoss() throws Exception {
        try (SqliteConnectionOwner owner = openOwner("ambiguous-same-token.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            trackItemWithHistory(owner, repository, "PICKUP", 1_000L);
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);
            UUID departureToken = UUID.randomUUID();

            // Committed. The caller never learns that, so it rearms and the next pass tries again.
            assertTrue(journal.recordLoss(loss(7L, departureToken))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS));
            repository.logHistory(history("PICKUP", 20_000L));
            owner.flush();
            clock.set(21_000L);

            assertTrue(
                journal.recordLoss(loss(9L, departureToken)).get(AWAIT_SECONDS, TimeUnit.SECONDS),
                "the resubmission was refused rather than recognised"
            );
            assertEquals(
                1,
                countAction(repository, "CLEARED"),
                "one disappearance was written twice"
            );
        }
    }

    /**
     * What a candidate that mints a token per request costs, stated as a test rather than a comment.
     *
     * <p>Same disappearance, same interleaving as above, only the token differs — and the loss is
     * written twice. This is the executable form of the obligation in
     * {@link com.itemguard.listeners.LossCandidate#departureToken()}: the token belongs to the
     * baseline's memory of the code going missing, not to the request or the in-flight entry.
     */
    @Test
    void theSameDisappearanceResubmittedWithAFreshTokenIsRecordedTwice() throws Exception {
        try (SqliteConnectionOwner owner = openOwner("ambiguous-fresh-token.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            trackItemWithHistory(owner, repository, "PICKUP", 1_000L);
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);

            assertTrue(journal.recordLoss(loss(7L, UUID.randomUUID()))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS));
            repository.logHistory(history("PICKUP", 20_000L));
            owner.flush();
            clock.set(21_000L);

            assertTrue(journal.recordLoss(loss(9L, UUID.randomUUID()))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(
                2,
                countAction(repository, "CLEARED"),
                "a fresh token no longer duplicates; the candidate contract may have moved"
            );
        }
    }

    /**
     * The coordinator carries the candidate's token; it does not invent one.
     *
     * <p>Minting inside the coordinator would look correct in every single-pass test and fail exactly
     * where it matters, because a rearm builds a new entry for the same disappearance. The second
     * request here is that case: a later generation, the same token.
     */
    @Test
    void theCoordinatorPassesTheCandidateTokenThroughUnchanged() {
        UUID departureToken = UUID.randomUUID();
        AtomicReference<LossRecord> captured = new AtomicReference<>();
        com.itemguard.listeners.LossJournal capturing = new com.itemguard.listeners.LossJournal() {
            @Override
            public CompletableFuture<String> lastRecordedAction(String code) {
                return CompletableFuture.completedFuture("PICKUP");
            }

            @Override
            public CompletableFuture<Boolean> recordLoss(LossRecord record) {
                captured.set(record);
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }
        };
        com.itemguard.listeners.ItemLossScanOffloadCoordinator coordinator =
            new com.itemguard.listeners.ItemLossScanOffloadCoordinator(
                capturing, Runnable::run, () -> true, new ScanEpochGenerator(() -> 1L)
            );

        long firstPass = coordinator.beginGeneration(playerUuid);
        assertTrue(coordinator.request(new TokenCandidate(departureToken, firstPass)));
        assertEquals(
            departureToken,
            captured.get().departureToken(),
            "the coordinator minted a token of its own"
        );

        long secondPass = coordinator.beginGeneration(playerUuid);
        assertTrue(coordinator.request(new TokenCandidate(departureToken, secondPass)));
        assertEquals(departureToken, captured.get().departureToken());
        assertNotEquals(
            firstPass,
            captured.get().generation(),
            "the two passes were meant to differ"
        );
    }

    /**
     * The coordinator commits, then the exact item is seen again before the write leaves the queue.
     *
     * <p>This is the one window neither half closes on its own, and the two guards that look like they
     * cover it do not. The coordinator's guard is
     * {@link com.itemguard.listeners.ItemLossScanOffloadCoordinator}'s commit: a compare-and-set on the
     * entry, made on the main thread <em>before</em> {@code recordLoss} is called. Once it wins, a later
     * {@code observePresence} finds the entry committed and deliberately leaves the in-flight write
     * alone. The journal's guard is the action fence inside the writing transaction, and it is a
     * different question entirely — it asks the database what the newest recorded action is. A sighting
     * is not an action. Nothing appends a row for "the stack is back in the inventory", so the fence
     * re-reads {@code PICKUP}, still finds the departure unexplained, and writes.
     *
     * <p>So the sequence below holds the database worker, lets the main thread settle and commit, and
     * then hands the coordinator a sighting of the same code and the same item UUID while the write sits
     * in the queue — with no history row written in between, which is what keeps the SQL fence blind.
     * Releasing the worker then writes {@code CLEARED} for an item the server can see. That is a
     * confirmed loss for an item that exists: the restore path's input, and a duplicate if it is taken.
     *
     * <p>Desired: the sighting cancels the write, so no {@code CLEARED} row and no {@code recorded()}.
     * The commit semantics documented on the coordinator say the opposite — past the fence the write is
     * the coordinator's to finish — so this test asserts the requirement, not the current behaviour.
     */
    @Test
    void aSightingWhileTheWriteIsQueuedMustNotRecordTheLoss() throws Exception {
        try (SqliteConnectionOwner owner = openOwner("sighting-after-commit.db");
             Cleanup released = () -> releaseBlockedWorkers(owner)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            trackItemWithHistory(owner, repository, "PICKUP", 1_000L);
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);
            assertEquals(1, repository.getHistoryCount(CODE), "the baseline history is not what it was");

            // Stands in for the main thread: nothing runs on it until this test drains it, so the
            // settle and the sighting can be placed on either side of the queued write.
            Queue<Runnable> mainThread = new ConcurrentLinkedQueue<>();
            CountDownLatch dispatched = new CountDownLatch(1);
            com.itemguard.listeners.ItemLossScanOffloadCoordinator coordinator =
                new com.itemguard.listeners.ItemLossScanOffloadCoordinator(
                    journal,
                    task -> {
                        mainThread.add(task);
                        dispatched.countDown();
                    },
                    () -> true,
                    new ScanEpochGenerator(clock::get)
                );

            long generation = coordinator.beginGeneration(playerUuid);
            SightedCandidate candidate = new SightedCandidate(generation);
            assertTrue(coordinator.request(candidate), "the candidate was refused before the race began");
            assertTrue(
                dispatched.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                "the journal read never hopped back to the main thread"
            );

            // From here the database worker is held, so the write the commit submits is observably
            // queued rather than executed, and the sighting lands strictly inside that gap.
            CountDownLatch writeGate = blockDatabaseThread(owner);
            runMainThread(mainThread);
            assertTrue(candidate.revalidated, "the settle never reached the commit point");

            coordinator.observePresence(CODE, itemUuid, playerUuid);

            writeGate.countDown();
            owner.flush();
            runMainThread(mainThread);

            assertEquals(
                0,
                countAction(repository, "CLEARED"),
                "a loss was written for an item the server saw before the write ran"
            );
            assertEquals(1, repository.getHistoryCount(CODE), "no row was meant to be appended");
            assertEquals("PICKUP", repository.getItem(CODE).orElseThrow().getLastAction());
            assertFalse(
                candidate.recorded,
                "the baseline was told to retire a code that had just been seen again"
            );
            assertTrue(failures.isEmpty(), "a cancelled write is a decision, not a failure");
        }
    }

    /** Runs everything the coordinator has posted so far, in the order it posted it. */
    private void runMainThread(Queue<Runnable> mainThread) {
        for (Runnable task = mainThread.poll(); task != null; task = mainThread.poll()) {
            task.run();
        }
    }

    /**
     * A candidate that is still missing when asked and records what the coordinator did about it.
     *
     * <p>The fields are touched only from the thread draining {@code mainThread}, which is the test
     * thread itself, because the coordinator promises every callback lands on the main thread.
     */
    private final class SightedCandidate implements com.itemguard.listeners.LossCandidate {

        private final UUID departureToken = UUID.randomUUID();
        private final long generation;

        private boolean revalidated;
        private boolean recorded;

        SightedCandidate(long generation) {
            this.generation = generation;
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public UUID itemUuid() {
            return itemUuid;
        }

        @Override
        public UUID playerUuid() {
            return playerUuid;
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public UUID departureToken() {
            return departureToken;
        }

        @Override
        public boolean stillMissing() {
            // True at the fence: the item comes back after the commit, not before it.
            revalidated = true;
            return true;
        }

        @Override
        public String location() {
            return "world (0, 64, 0)";
        }

        @Override
        public void recorded() {
            recorded = true;
        }

        @Override
        public void rearmBaseline() {
            // Not asserted: a cancelled write may hand the code back or not, and after a sighting
            // nothing is owed either way. The claim under test is that no loss is recorded.
        }

        /** No sighting case retires a code; a stray retirement would fail the recorded assertions. */
        @Override
        public void unrecordable(String reason) {
            throw new AssertionError("A sighted candidate must not be retired: " + reason);
        }
    }

    /** A candidate whose disappearance has an identity of its own, as the contract requires. */
    private final class TokenCandidate implements com.itemguard.listeners.LossCandidate {

        private final UUID departureToken;
        private final long generation;

        TokenCandidate(UUID departureToken, long generation) {
            this.departureToken = departureToken;
            this.generation = generation;
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public UUID itemUuid() {
            return itemUuid;
        }

        @Override
        public UUID playerUuid() {
            return playerUuid;
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public UUID departureToken() {
            return departureToken;
        }

        @Override
        public String location() {
            return "world (0, 64, 0)";
        }

        @Override
        public boolean stillMissing() {
            return true;
        }

        @Override
        public void recorded() {
        }

        @Override
        public void rearmBaseline() {
        }

        /** These codes are all tracked, so retirement here would mean the fence misfired. */
        @Override
        public void unrecordable(String reason) {
            throw new AssertionError("A tracked candidate must not be retired: " + reason);
        }
    }

    /**
     * The captured position reaches the row, in both places the old synchronous path wrote it.
     *
     * <p>{@code ItemTrackingService.recordLossByIdentity} passed the player's location into
     * {@code updateItemLastAction} and into the {@code ItemHistory} it logged, so an inventory clear
     * left a record of where it happened. The offloaded path captures that position on the main
     * thread instead of reading a {@code Location} off it — but a capture nothing persists is not a
     * capture, and "where did this vanish" is most of what an admin has to work with.
     *
     * <p>RED: the write currently moves {@code last_action} only and binds the history row's location
     * to null, so the summary still reads the item's previous position and the row reads nothing.
     */
    @Test
    void theCapturedLocationIsPersistedWithTheLoss() throws Exception {
        try (SqliteConnectionOwner owner = openOwner("loss-location.db")) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            trackItemWithHistory(owner, repository, "PICKUP", 1_000L);
            SqliteLossJournal journal = new SqliteLossJournal(owner, clock::get);

            assertTrue(journal.recordLoss(lossAt(7L, UUID.randomUUID(), "world (10, 64, -7)"))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS));

            assertEquals(
                "world (10, 64, -7)",
                repository.getItem(CODE).orElseThrow().getLastLocation(),
                "the summary still points at where the item used to be"
            );
            assertEquals(
                "world (10, 64, -7)",
                repository.getHistory(CODE, 1).getFirst().getLocation(),
                "the loss row records no position at all"
            );
        }
    }

    private SqliteConnectionOwner openOwner(String fileName) {
        return new SqliteConnectionOwner(tempDir.resolve(fileName), failures::add);
    }

    /**
     * Occupies the single database thread until the returned latch is released, so anything submitted
     * in between is observably queued rather than executed.
     *
     * <p>The latch is registered before the task is submitted, because the test that releases it may
     * never get that far: a failed assertion or a journal that throws leaves the worker parked, and
     * only a registered latch can be released by {@link #releaseBlockedWorkers}.
     */
    private CountDownLatch blockDatabaseThread(SqliteConnectionOwner owner) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        owner.execute(connection -> {
            entered.countDown();
            gate.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            return null;
        });
        assertTrue(
            entered.await(AWAIT_SECONDS, TimeUnit.SECONDS),
            "database thread never picked up the blocking task"
        );
        return gate;
    }

    /**
     * Unparks every blocked worker and waits for the queue to drain.
     *
     * <p>Must run before the owner is closed, which is why it is a resource declared after the owner
     * rather than a line at the end of a test body: resources close in reverse order, so this runs on
     * the way out however the test ended. Closing first would queue the close behind a worker waiting
     * on a latch nobody is going to release — the close then burns its full timeout, gives up, keeps
     * the process lock, and the temporary database file cannot be deleted. The flush is what makes
     * the teardown a join rather than a hope: it is FIFO behind the task just released, so it answers
     * only once that task has finished with the connection.
     */
    private void releaseBlockedWorkers(SqliteConnectionOwner owner) {
        try {
            for (CountDownLatch gate : gates) {
                gate.countDown();
            }
        } finally {
            gates.clear();
            try {
                owner.flush();
            } catch (RuntimeException unusable) {
                // A closed or already broken owner has no queue left to drain; closing still applies.
            }
        }
    }

    /** A teardown step shaped as a resource, so it can be sequenced against an owner's close. */
    private interface Cleanup extends AutoCloseable {
        @Override
        void close();
    }

    private void trackItemWithHistory(
        SqliteConnectionOwner owner,
        ItemSqliteRepository repository,
        String action,
        long timestamp
    ) {
        repository.saveItem(item());
        repository.updateLastAction(CODE, action, "world (0, 64, 0)", "Thanh", playerUuid, timestamp);
        repository.logHistory(history(action, timestamp));
        owner.flush();
    }

    private ItemData item() {
        ItemData item = new ItemData(CODE, itemUuid);
        item.setOwnerUuid(playerUuid);
        item.setOwnerName("Thanh");
        item.setMaterial(Material.DIAMOND_SWORD);
        item.setItemName("Guarded Sword");
        item.setCreatedAt(1_000L);
        item.setLastSeenAt(1_000L);
        item.setLastAction("SPAWN");
        item.setDetectionCount(1);
        item.setLastLocation("world (0, 64, 0)");
        return item;
    }

    private ItemHistory history(String action, long timestamp) {
        ItemHistory history = new ItemHistory(
            CODE, itemUuid, action, "Thanh", playerUuid, "world (0, 64, 0)"
        );
        history.setTimestamp(timestamp);
        return history;
    }

    /**
     * One departure. The token is derived from the generation so that, for the tests that predate the
     * token, the same call still means the same disappearance and a different call a different one.
     */
    private LossRecord loss(long generation) {
        return loss(generation, new UUID(0L, generation));
    }

    /** Journal-level tests have no coordinator behind them, so the permit is always there to take. */
    private LossRecord loss(long generation, UUID departureToken) {
        // The item's last known position, which is where these fixtures put it.
        return lossAt(generation, departureToken, "world (0, 64, 0)");
    }

    private LossRecord lossAt(long generation, UUID departureToken, String location) {
        return new LossRecord(
            CODE,
            itemUuid,
            ItemLossPolicy.forInventoryRemoval(),
            playerUuid,
            generation,
            departureToken,
            () -> true,
            location
        );
    }

    private long countAction(ItemSqliteRepository repository, String action) {
        return repository.getHistory(CODE, 1_000).stream()
            .filter(row -> action.equals(row.getAction()))
            .count();
    }
}
