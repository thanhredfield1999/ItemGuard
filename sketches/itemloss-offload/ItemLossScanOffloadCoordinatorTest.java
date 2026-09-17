package com.itemguard.listeners;

// SUPERSEDED: the seam now exists and the live copy of this suite is
// src/test/java/com/itemguard/listeners/ItemLossScanOffloadCoordinatorTest.java, which is the one
// Maven runs. This staged copy is kept only as the original proposal; edit the live copy instead.
// The live copy differs in one place: it settles the journal worker before counting the read in
// theHistoryReadRunsOffTheScanThread, because the read is logged asynchronously and the count was
// otherwise racy.

import com.itemguard.dupe.ScanEpochGenerator;
import com.itemguard.restore.LossReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordering of the offloaded loss scan, in isolation from the listener and from Bukkit.
 *
 * <p>The parent regression only says the three database calls must leave the scan thread. Every
 * remedy that satisfies it introduces a race the synchronous code did not have: between the moment
 * the scan decides a code is missing and the moment the journal answers, the item can come back, be
 * dropped on purpose, the player can quit, or the plugin can disable. These tests pin what the
 * coordinator must do in each of those windows, and nothing about the listener's own snapshotting.
 *
 * <p>Determinism is bought by driving both sides by hand: the journal answers only when the test
 * completes its future, and the main-thread dispatcher is a queue the test drains. No sleeps, no
 * timeouts as synchronisation. The one real thread exists so that "off the scan thread" is an
 * observation rather than an assumption.
 */
class ItemLossScanOffloadCoordinatorTest {

    private static final String CODE = "ABC123";

    private final Fixture fixture = new Fixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void theHistoryReadRunsOffTheScanThread() {
        Candidate candidate = fixture.candidate();

        Thread scanThread = Thread.currentThread();
        assertTrue(fixture.coordinator.request(candidate), "the first request for a code is accepted");

        assertEquals(1, fixture.journal.reads.size(), "the read must be submitted, not skipped");
        assertFalse(fixture.journal.threadsOf(fixture.journal.reads).contains(scanThread),
            "the history read ran on the scan thread: " + fixture.journal.reads);
    }

    @Test
    void theLossWriteRunsOffTheScanThread() {
        Candidate candidate = fixture.candidate();
        Thread scanThread = Thread.currentThread();

        fixture.coordinator.request(candidate);
        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();
        fixture.journal.answerWrite(true);
        fixture.drainMainThread();

        assertEquals(1, fixture.journal.writes.size(), "the loss must still be recorded");
        assertFalse(fixture.journal.threadsOf(fixture.journal.writes).contains(scanThread),
            "the loss write ran on the scan thread: " + fixture.journal.writes);
    }

    @Test
    void nothingTouchesTheCandidateOffTheMainThread() {
        // stillMissing/recorded/rearmBaseline read Bukkit state. The candidate records the thread it
        // was called on; only threads the test itself drained the dispatcher on are legal.
        Candidate candidate = fixture.candidate();

        fixture.coordinator.request(candidate);
        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();
        fixture.journal.answerWrite(true);
        fixture.drainMainThread();

        assertEquals(List.of(Thread.currentThread()), candidate.distinctCallingThreads(),
            "candidate callbacks must run only on the thread that drains the dispatcher: "
                + candidate.distinctCallingThreads());
    }

    @Test
    void anItemThatReappearsBeforeTheAnswerIsNotRecordedAsLost() {
        Candidate candidate = fixture.candidate();
        fixture.coordinator.request(candidate);

        // The tick after the scan sees it back in the inventory, while the read is still in flight.
        fixture.coordinator.observePresence(CODE, candidate.itemUuid(), candidate.playerUuid());

        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();

        assertTrue(fixture.journal.writes.isEmpty(),
            "a code seen again before the answer must not be written as a loss: " + fixture.journal.writes);
        assertEquals(0, candidate.recordedCount(), "recorded() must not fire for a reappearance");
    }

    @Test
    void aDeliberateDropAfterTheReadInvalidatesTheStaleResult() {
        // The ordinary false positive: the scan misses the stack for one tick, the player drops it on
        // purpose, and the in-flight answer would otherwise report CLEARED over a real DROP.
        Candidate candidate = fixture.candidate();
        fixture.coordinator.request(candidate);
        fixture.journal.answerRead("PICKUP");

        fixture.coordinator.observeExplainedDeparture(CODE, candidate.itemUuid(), candidate.playerUuid());
        fixture.drainMainThread();

        assertTrue(fixture.journal.writes.isEmpty(),
            "an explained departure must invalidate the in-flight result: " + fixture.journal.writes);
    }

    @Test
    void aCallbackFromAnOlderGenerationCannotRecordAgainstTheCurrentOne() {
        Candidate stale = fixture.candidate();
        fixture.coordinator.request(stale);

        // A new scan generation opens for the same player while the first read is in flight.
        long newer = fixture.coordinator.beginGeneration(stale.playerUuid());
        assertTrue(newer > stale.generation(), "generations must advance monotonically");

        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();

        assertTrue(fixture.journal.writes.isEmpty(),
            "a callback from a superseded generation must be dropped: " + fixture.journal.writes);
        assertEquals(0, stale.recordedCount(), "the stale callback must not reach recorded()");
    }

    @Test
    void aRejectedSubmissionRearmsTheBaselineInsteadOfLosingIt() {
        // Queue full or executor shut down. Dropping the candidate silently would retire the code
        // from lastSeen and the loss would never be noticed again; the baseline must be rearmed so
        // the next tick retries.
        fixture.journal.rejectSubmissions.set(true);
        Candidate candidate = fixture.candidate();

        assertFalse(fixture.coordinator.request(candidate), "a rejected submission is not accepted");
        assertEquals(1, candidate.rearmCount(), "the baseline must be rearmed on rejection");
        assertEquals(0, candidate.recordedCount(), "nothing was recorded");

        fixture.journal.rejectSubmissions.set(false);
        assertTrue(fixture.coordinator.request(candidate), "the next tick may retry the same code");
    }

    @Test
    void aJournalTimeoutRearmsTheBaselineInsteadOfLosingIt() {
        Candidate candidate = fixture.candidate();
        fixture.coordinator.request(candidate);

        fixture.journal.failRead(new TimeoutException("journal read timed out"));
        fixture.drainMainThread();

        assertTrue(fixture.journal.writes.isEmpty(), "a timed-out read records nothing");
        assertEquals(1, candidate.rearmCount(), "the baseline must survive a timed-out read");
    }

    @Test
    void quitAndDisableBothInvalidateInFlightWork() {
        Candidate quitting = fixture.candidate();
        fixture.coordinator.request(quitting);
        fixture.coordinator.invalidate(quitting.playerUuid());
        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();

        assertTrue(fixture.journal.writes.isEmpty(),
            "a quit must invalidate that player's in-flight scan: " + fixture.journal.writes);

        Candidate disabling = fixture.candidate();
        fixture.coordinator.request(disabling);
        fixture.enabled.set(false);
        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();

        assertTrue(fixture.journal.writes.isEmpty(),
            "a disabled plugin must not keep writing: " + fixture.journal.writes);
    }

    @Test
    void oneDepartureIsRecordedExactlyOnce() {
        Candidate candidate = fixture.candidate();

        assertTrue(fixture.coordinator.request(candidate), "the first request is accepted");
        assertFalse(fixture.coordinator.request(candidate),
            "the same code must not be submitted twice while one is in flight");

        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();
        fixture.journal.answerWrite(true);
        fixture.drainMainThread();

        assertEquals(1, fixture.journal.writes.size(), "exactly one write: " + fixture.journal.writes);
        assertEquals(1, candidate.recordedCount(), "exactly one recorded() callback");

        // And the slot is released again once the write completes.
        assertTrue(fixture.coordinator.request(fixture.candidate()),
            "a later departure of the same code must still be accepted");
    }

    /** One journal call: which operation, and which thread made it. */
    private record JournalCall(String operation, Thread thread) {
        @Override
        public String toString() {
            return operation + "() on " + thread.getName();
        }
    }

    /** The coordinator plus hand-driven journal and dispatcher. */
    private static final class Fixture {

        final RecordingJournal journal = new RecordingJournal();
        final Deque<Runnable> mainThread = new ArrayDeque<>();
        final AtomicBoolean enabled = new AtomicBoolean(true);
        final AtomicLong clock = new AtomicLong(1_000L);
        final ItemLossScanOffloadCoordinator coordinator = new ItemLossScanOffloadCoordinator(
            journal, mainThread::add, enabled::get, new ScanEpochGenerator(clock::incrementAndGet));

        Candidate candidate() {
            UUID player = UUID.randomUUID();
            return new Candidate(CODE, UUID.randomUUID(), player, coordinator.beginGeneration(player));
        }

        void drainMainThread() {
            journal.awaitQuiet();
            for (Runnable task = mainThread.poll(); task != null; task = mainThread.poll()) {
                task.run();
                journal.awaitQuiet();
            }
        }

        void close() {
            coordinator.shutdown();
            journal.close();
        }
    }

    /**
     * A journal whose work runs on a real worker thread but whose answers are given by the test.
     *
     * <p>The worker exists only so the calling thread of each operation is a fact; completion order
     * stays under the test's control.
     */
    private static final class RecordingJournal implements LossJournal {

        final List<JournalCall> reads = Collections.synchronizedList(new ArrayList<>());
        final List<JournalCall> writes = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean rejectSubmissions = new AtomicBoolean();

        private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "itemguard-journal-test");
            thread.setDaemon(true);
            return thread;
        });
        private final Deque<CompletableFuture<String>> pendingReads = new ArrayDeque<>();
        private final Deque<CompletableFuture<Boolean>> pendingWrites = new ArrayDeque<>();

        @Override
        public CompletableFuture<String> lastRecordedAction(String code) {
            return submit("lastRecordedAction", reads, pendingReads);
        }

        @Override
        public CompletableFuture<Boolean> recordLoss(LossRecord record) {
            return submit("recordLoss", writes, pendingWrites);
        }

        private <T> CompletableFuture<T> submit(
            String operation, List<JournalCall> log, Deque<CompletableFuture<T>> pending) {
            if (rejectSubmissions.get()) {
                throw new RejectedExecutionException("journal queue full");
            }
            CompletableFuture<T> future = new CompletableFuture<>();
            pending.add(future);
            worker.execute(() -> log.add(new JournalCall(operation, Thread.currentThread())));
            return future;
        }

        void answerRead(String action) {
            complete(pendingReads.poll(), future -> future.complete(action));
        }

        void failRead(Throwable failure) {
            complete(pendingReads.poll(), future -> future.completeExceptionally(failure));
        }

        void answerWrite(boolean written) {
            complete(pendingWrites.poll(), future -> future.complete(written));
        }

        private <T> void complete(
            CompletableFuture<T> future, java.util.function.Consumer<CompletableFuture<T>> answer) {
            if (future == null) {
                throw new IllegalStateException("no journal call is in flight");
            }
            // Answered from the worker so the coordinator's continuation runs off the scan thread,
            // which is what production would do.
            worker.execute(() -> answer.accept(future));
            awaitQuiet();
        }

        List<Thread> threadsOf(List<JournalCall> log) {
            synchronized (log) {
                return log.stream().map(JournalCall::thread).distinct().toList();
            }
        }

        /** Blocks until the worker has no queued work, so assertions see a settled state. */
        void awaitQuiet() {
            try {
                worker.submit(() -> null).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("journal worker did not settle", e);
            }
        }

        void close() {
            worker.shutdownNow();
        }
    }

    /** A departed stack, recording every callback and the thread it arrived on. */
    private static final class Candidate implements LossCandidate {

        private final String code;
        private final UUID itemUuid;
        private final UUID playerUuid;
        private final long generation;
        private final List<Thread> callingThreads = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger recorded = new AtomicInteger();
        private final AtomicInteger rearmed = new AtomicInteger();

        Candidate(String code, UUID itemUuid, UUID playerUuid, long generation) {
            this.code = code;
            this.itemUuid = itemUuid;
            this.playerUuid = playerUuid;
            this.generation = generation;
        }

        @Override
        public String code() {
            return code;
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
        public boolean stillMissing() {
            callingThreads.add(Thread.currentThread());
            return true;
        }

        @Override
        public void recorded() {
            callingThreads.add(Thread.currentThread());
            recorded.incrementAndGet();
        }

        @Override
        public void rearmBaseline() {
            callingThreads.add(Thread.currentThread());
            rearmed.incrementAndGet();
        }

        int recordedCount() {
            return recorded.get();
        }

        int rearmCount() {
            return rearmed.get();
        }

        List<Thread> distinctCallingThreads() {
            synchronized (callingThreads) {
                return callingThreads.stream().distinct().toList();
            }
        }
    }
}
