package com.itemguard.listeners;

import com.itemguard.dupe.ScanEpochGenerator;
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
 *
 * <p>The window between the main-thread approval and the journal actually running the write is
 * pinned separately, in {@link ItemLossScanOffloadFenceTest}.
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

        fixture.journal.awaitQuiet();
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

    @Test
    void aSecondCandidateForTheSameCodeKeepsItsOwnBaseline() {
        // A duped code is in two inventories at once, which is the case this plugin exists for. The
        // second departure is a different stack held by a different player: it is a second loss, not
        // a second sighting of the first one, and the first candidate's entry owns only its own
        // baseline. Refusing the slot is right; refusing it without handing the code back retires it
        // from lastSeen and no later scan will look at it again.
        Candidate first = fixture.candidate();
        assertTrue(fixture.coordinator.request(first), "the first candidate takes the slot");

        Candidate second = fixture.candidate();
        assertFalse(fixture.coordinator.request(second), "one in-flight candidate per code");

        assertEquals(1, second.rearmCount(), "a refused second candidate must keep its own baseline");
        assertEquals(0, first.rearmCount(), "the accepted candidate still owns the in-flight baseline");
        assertEquals(0, second.recordedCount(), "nothing was recorded for the refused candidate");
    }

    @Test
    void aRefusedMainThreadDispatchDoesNotSilentlyRetireTheCode() {
        // The scheduler refuses the hop back while the plugin is still enabled -- a bounded queue,
        // not a disable. request() already returned true, so the caller has retired its baseline and
        // only the coordinator still knows the code is missing. The refusal is discovered on the
        // journal thread, where the candidate may not be touched at all, so the recovery has to wait
        // for a main thread that belongs to this player: the next scan pass.
        Candidate candidate = fixture.candidate();
        assertTrue(fixture.coordinator.request(candidate), "the caller has retired its baseline");

        fixture.rejectDispatch.set(true);
        fixture.journal.answerRead("PICKUP");
        fixture.journal.awaitQuiet();

        assertEquals(List.of(), candidate.distinctCallingThreads(),
            "a refused dispatch must not touch the candidate off the main thread: "
                + candidate.distinctCallingThreads());

        fixture.rejectDispatch.set(false);
        fixture.coordinator.beginGeneration(candidate.playerUuid());
        fixture.drainMainThread();

        assertEquals(1, candidate.rearmCount(),
            "the next scan pass must hand back the baseline of an undispatchable candidate");
        assertEquals(List.of(Thread.currentThread()), candidate.distinctCallingThreads(),
            "the rearm must happen on the main thread: " + candidate.distinctCallingThreads());
        assertTrue(fixture.journal.writes.isEmpty(), "nothing was adjudicated, so nothing is written");
    }

    @Test
    void aDispatchRefusedByADisableStaysSilent() {
        // The same refusal, from the opposite cause: Bukkit stops accepting tasks once the plugin is
        // disabling. There is no later pass to recover into and no legal thread to call back on, so
        // giving up without touching the candidate is the correct answer. This is the ceiling on the
        // previous test -- a fix that rearms every refused dispatch breaks the disable path here.
        Candidate candidate = fixture.candidate();
        fixture.coordinator.request(candidate);

        fixture.coordinator.shutdown();
        fixture.rejectDispatch.set(true);
        fixture.journal.answerRead("PICKUP");
        fixture.journal.awaitQuiet();

        fixture.rejectDispatch.set(false);
        fixture.coordinator.beginGeneration(candidate.playerUuid());
        fixture.drainMainThread();

        assertEquals(0, candidate.rearmCount(), "a disable has no baseline left to rearm");
        assertEquals(List.of(), candidate.distinctCallingThreads(),
            "a disable must not call back into the candidate at all: "
                + candidate.distinctCallingThreads());
        assertTrue(fixture.journal.writes.isEmpty(), "a disabled coordinator writes nothing");
    }

    @Test
    void aDisableAfterARefusedDispatchClearsTheCandidateWithoutCallingBack() {
        // The two refusals in the other order. The dispatch is refused while the plugin is up, so the
        // candidate is held for a later pass to hand back -- and then the server stops before any pass
        // arrives. Holding it is only safe if the disable path lets go of it, and letting go must stay
        // silent: shutdown runs on a thread that is finished with this player, and a callback there
        // would reach into Bukkit state that is already being torn down.
        Candidate candidate = fixture.candidate();
        assertTrue(fixture.coordinator.request(candidate), "the caller has retired its baseline");

        fixture.rejectDispatch.set(true);
        fixture.journal.answerRead("PICKUP");
        fixture.journal.awaitQuiet();
        fixture.rejectDispatch.set(false);

        fixture.coordinator.shutdown();
        fixture.coordinator.beginGeneration(candidate.playerUuid());
        fixture.drainMainThread();

        assertEquals(0, candidate.rearmCount(), "a disable has no later pass to rearm into");
        assertEquals(List.of(), candidate.distinctCallingThreads(),
            "a disable must not call back into a held candidate: " + candidate.distinctCallingThreads());
        assertTrue(fixture.journal.writes.isEmpty(), "nothing was adjudicated, so nothing is written");
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
        final AtomicBoolean rejectDispatch = new AtomicBoolean();
        final AtomicLong clock = new AtomicLong(1_000L);
        final ItemLossScanOffloadCoordinator coordinator = new ItemLossScanOffloadCoordinator(
            journal, this::dispatchToMainThread, enabled::get,
            new ScanEpochGenerator(clock::incrementAndGet));

        /** Bukkit's scheduler throws rather than returns when it will not take the task. */
        void dispatchToMainThread(Runnable task) {
            if (rejectDispatch.get()) {
                throw new IllegalStateException("main thread scheduler refused the task");
            }
            mainThread.add(task);
        }

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

    /**
     * The token must outlive the attempt and die with the disappearance, not the other way round.
     *
     * <p>This is the one thing the journal cannot check and the coordinator is forbidden from doing
     * for itself. A rearm builds a new candidate for a departure that never ended, and that has to
     * carry the old identity or the loss is written twice. A departure that ended — the item was
     * actually seen — and then began again is a different event, and reusing the old identity there
     * would suppress a real loss, which is the worse of the two failures.
     *
     * <p>Identity separation is asserted alongside it because the book is keyed on the pair: a code
     * carried by a different item UUID, or the same pair under a different player, is somebody else's
     * departure.
     */
    @Test
    void aDepartureTokenSurvivesARearmAndIsReplacedOnlyAfterTheItemIsSeenAgain() {
        ItemLossListener.DepartureTokenBook book = new ItemLossListener.DepartureTokenBook();
        UUID player = UUID.randomUUID();
        UUID itemUuid = UUID.randomUUID();

        UUID first = book.tokenFor(player, "AB12CD", itemUuid).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(
            first,
            book.tokenFor(player, "AB12CD", itemUuid).orElseThrow(),
            "the token did not survive the rearm, so the retry looks like a new loss"
        );

        // The item is genuinely back in hand, and only then goes missing again.
        book.observePresent(player, "AB12CD", itemUuid);
        UUID second = book.tokenFor(player, "AB12CD", itemUuid).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNotEquals(
            first,
            second,
            "a new disappearance reused the finished departure's token"
        );

        org.junit.jupiter.api.Assertions.assertNotEquals(
            second,
            book.tokenFor(player, "AB12CD", UUID.randomUUID()).orElseThrow(),
            "a different item under the same code shared a token"
        );
        org.junit.jupiter.api.Assertions.assertNotEquals(
            second,
            book.tokenFor(UUID.randomUUID(), "AB12CD", itemUuid).orElseThrow(),
            "a different player shared a token"
        );

        book.forgetPlayer(player);
        org.junit.jupiter.api.Assertions.assertNotEquals(
            second,
            book.tokenFor(player, "AB12CD", itemUuid).orElseThrow(),
            "a quit did not drop the player's departures"
        );
    }

    /**
     * A full book refuses new departures and never touches the ones it already holds.
     *
     * <p>Evicting to make room would be the worst of both: the evicted departure is still missing, so
     * its next resubmission arrives with a fresh identity and the loss is written twice — quietly, and
     * only under the load that made the book fill up. Refusing costs the new departure its offload
     * instead, and the caller must leave that code missing so a later pass can pick it up.
     */
    @Test
    void aFullBookRefusesNewDeparturesRatherThanEvictingLiveOnes() {
        ItemLossListener.DepartureTokenBook book = new ItemLossListener.DepartureTokenBook(2);
        UUID player = UUID.randomUUID();
        UUID firstItem = UUID.randomUUID();
        UUID secondItem = UUID.randomUUID();

        UUID firstToken = book.tokenFor(player, "AAAAAA", firstItem).orElseThrow();
        UUID secondToken = book.tokenFor(player, "BBBBBB", secondItem).orElseThrow();

        org.junit.jupiter.api.Assertions.assertTrue(
            book.tokenFor(player, "CCCCCC", UUID.randomUUID()).isEmpty(),
            "a new departure was admitted past the cap"
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            firstToken,
            book.tokenFor(player, "AAAAAA", firstItem).orElseThrow(),
            "a live departure lost its token to make room"
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            secondToken,
            book.tokenFor(player, "BBBBBB", secondItem).orElseThrow(),
            "a live departure lost its token to make room"
        );

        // The refusal is not permanent: an item coming back frees the slot it was holding.
        book.observePresent(player, "AAAAAA", firstItem);
        UUID admitted = book.tokenFor(player, "CCCCCC", UUID.randomUUID()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNotEquals(firstToken, admitted);
        org.junit.jupiter.api.Assertions.assertNotEquals(secondToken, admitted);
    }

    /**
     * Presence retires exactly one departure, and a quit retires all of a player's.
     *
     * <p>The pair is the identity. A stack returning under the same code is not this stack, and
     * another player's copy is not this player's departure; treating either as the end of this
     * departure would mint a new token for a disappearance that never stopped.
     */
    @Test
    void presenceRetiresOnlyTheExactIdentity() {
        ItemLossListener.DepartureTokenBook book = new ItemLossListener.DepartureTokenBook(4);
        UUID player = UUID.randomUUID();
        UUID itemUuid = UUID.randomUUID();

        UUID token = book.tokenFor(player, "AB12CD", itemUuid).orElseThrow();

        book.observePresent(player, "AB12CD", UUID.randomUUID());
        org.junit.jupiter.api.Assertions.assertEquals(
            token,
            book.tokenFor(player, "AB12CD", itemUuid).orElseThrow(),
            "a different stack under the same code retired this departure"
        );

        book.observePresent(UUID.randomUUID(), "AB12CD", itemUuid);
        org.junit.jupiter.api.Assertions.assertEquals(
            token,
            book.tokenFor(player, "AB12CD", itemUuid).orElseThrow(),
            "another player's return retired this departure"
        );

        // A player the book has never heard of is not an error, just nothing to do.
        book.observePresent(UUID.randomUUID(), "ZZ99ZZ", UUID.randomUUID());
        book.forgetPlayer(UUID.randomUUID());

        book.observePresent(player, "AB12CD", itemUuid);
        org.junit.jupiter.api.Assertions.assertNotEquals(
            token,
            book.tokenFor(player, "AB12CD", itemUuid).orElseThrow(),
            "the departure was not retired by the item actually coming back"
        );
    }

    /** A departed stack, recording every callback and the thread it arrived on. */
    private static final class Candidate implements LossCandidate {

        private final String code;
        private final UUID itemUuid;
        private final UUID playerUuid;
        private final long generation;
        private final UUID departureToken = UUID.randomUUID();
        private final List<Thread> callingThreads = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger recorded = new AtomicInteger();
        private final AtomicInteger rearmed = new AtomicInteger();
        private final AtomicInteger unrecordable = new AtomicInteger();

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

        /** One candidate object is one disappearance here, so the token is stable for its lifetime. */
        @Override
        public UUID departureToken() {
            return departureToken;
        }

        /** Formatted on the main thread in production; a fixed string is all this test needs. */
        @Override
        public String location() {
            return "world (0, 64, 0)";
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

        /** Retirement without a loss. Counted apart from both, and pinned in the livelock test. */
        @Override
        public void unrecordable(String reason) {
            callingThreads.add(Thread.currentThread());
            unrecordable.incrementAndGet();
        }

        int unrecordableCount() {
            return unrecordable.get();
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
