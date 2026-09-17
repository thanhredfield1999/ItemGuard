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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window the sibling suite cannot reach: between the main thread approving a loss and the
 * journal actually running the write.
 *
 * <p>{@link ItemLossScanOffloadCoordinatorTest} pins invalidations that arrive while the read is in
 * flight, and an implementation passes all of them by rechecking its flags once the answer comes
 * back. That is not enough. Revalidation is itself a call into listener code that can fire a quit
 * handler, and another thread can invalidate while the approving thread is between its last check
 * and its call to the journal. A recheck-then-submit implementation writes a loss it has already
 * agreed to abandon; the contract needs the check and the submission to be one indivisible step.
 *
 * <p>So every test here moves the invalidation into that window, by driving it from inside the
 * revalidation callback and from inside the journal call — the two points the coordinator cannot
 * schedule around. The property is two-directional and stated as such: an invalidation before the
 * approval means no write is ever submitted, an invalidation between approval and the worker's claim
 * cancels the queued write, and an invalidation after that claim leaves the committed write
 * alone rather than rearming a baseline for a loss that is already on disk.
 *
 * <p>No sleeps: futures are completed by hand and every helper thread is joined.
 */
class ItemLossScanOffloadFenceTest {

    private static final String CODE = "ABC123";

    private final Fixture fixture = new Fixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void anInvalidationDuringRevalidationCannotBeOvertakenByTheWrite() {
        // The quit lands while stillMissing() is running: after any precondition the coordinator
        // checked on the way in, and before it can submit anything.
        Candidate candidate = fixture.candidate();
        fixture.coordinator.request(candidate);
        candidate.duringRevalidation(() -> fixture.coordinator.invalidate(candidate.playerUuid()));

        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();

        assertTrue(fixture.journal.writes.isEmpty(),
            "an invalidation inside the approval window must stop the write: " + fixture.journal.writes);
        assertEquals(0, candidate.recordedCount(), "nothing may be reported as recorded");
    }

    @Test
    void anInvalidationFromAnotherThreadDuringRevalidationCannotBeOvertakenByTheWrite() {
        // Same window, but the invalidation genuinely races: it is complete on another thread before
        // revalidation returns, so only a fence the approving thread cannot step over will see it.
        Candidate candidate = fixture.candidate();
        fixture.coordinator.request(candidate);
        candidate.duringRevalidation(() ->
            onAnotherThread(() -> fixture.coordinator.invalidate(candidate.playerUuid())));

        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();

        assertTrue(fixture.journal.writes.isEmpty(),
            "a concurrent invalidation must be visible at the commit point: " + fixture.journal.writes);
    }

    @Test
    void aReappearanceDuringRevalidationCannotBeOvertakenByTheWrite() {
        Candidate candidate = fixture.candidate();
        fixture.coordinator.request(candidate);
        candidate.duringRevalidation(() ->
            fixture.coordinator.observePresence(CODE, candidate.itemUuid(), candidate.playerUuid()));

        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();

        assertTrue(fixture.journal.writes.isEmpty(),
            "a code seen again inside the approval window must not be written: " + fixture.journal.writes);
    }

    @Test
    void aNewGenerationDuringRevalidationCannotBeOvertakenByTheWrite() {
        Candidate candidate = fixture.candidate();
        fixture.coordinator.request(candidate);
        candidate.duringRevalidation(() -> fixture.coordinator.beginGeneration(candidate.playerUuid()));

        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();

        assertTrue(fixture.journal.writes.isEmpty(),
            "a superseded pass must not write after the newer one opened: " + fixture.journal.writes);
    }

    @Test
    void aDisableDuringRevalidationCannotBeOvertakenByTheWrite() {
        Candidate candidate = fixture.candidate();
        fixture.coordinator.request(candidate);
        candidate.duringRevalidation(() -> fixture.enabled.set(false));

        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();

        assertTrue(fixture.journal.writes.isEmpty(),
            "a plugin disabled inside the approval window must not write: " + fixture.journal.writes);
    }

    @Test
    void anInvalidationAfterTheCommitPointLeavesTheWriteAlone() {
        // The other direction, and the boundary moved: the commit point is no longer the coordinator's
        // submission but the worker's claim on the write permit, which HandJournal takes above before
        // running this hook. Past that claim the write is a fact in progress — revoking it would rearm
        // a baseline for a loss that is about to be on disk, and the next scan would report the same
        // loss a second time. A sighting that arrives before the claim is a different case entirely,
        // and it now cancels the write; see the coordinator's linearisation note.
        Candidate candidate = fixture.candidate();
        fixture.coordinator.request(candidate);
        fixture.journal.duringWrite(() ->
            onAnotherThread(() -> fixture.coordinator.invalidate(candidate.playerUuid())));

        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();
        assertEquals(1, fixture.journal.writes.size(), "the committed write must reach the journal");

        fixture.journal.answerWrite(true);
        fixture.drainMainThread();

        assertEquals(1, candidate.recordedCount(), "the committed write is reported exactly once");
        assertEquals(0, candidate.rearmCount(),
            "a committed write must not also rearm the baseline, or the loss is reported twice");

        // The slot is released even though the invalidation ran while the write was in flight.
        Candidate later = fixture.candidate();
        assertTrue(fixture.coordinator.request(later), "the code must be requestable again");
    }

    @Test
    void pendingCandidatesAreBoundedAndRefusalRearmsTheBaseline() {
        // A stalled journal must cost a fixed amount of memory. Refusing is safe only because the
        // baseline goes back: the code stays visible to the next scan instead of being retired.
        Fixture bounded = new Fixture(2);
        try {
            Candidate first = bounded.candidate("CODE01");
            Candidate second = bounded.candidate("CODE02");
            Candidate third = bounded.candidate("CODE03");

            assertTrue(bounded.coordinator.request(first), "the first candidate fits");
            assertTrue(bounded.coordinator.request(second), "the second candidate fits");
            assertFalse(bounded.coordinator.request(third), "the third exceeds the bound");

            assertEquals(2, bounded.journal.readsSubmitted(), "no read may be submitted beyond the bound");
            assertEquals(1, third.rearmCount(), "a refused candidate keeps its baseline");
            assertEquals(0, third.recordedCount(), "nothing was recorded");

            // Freeing one slot lets the refused code through on a later tick.
            bounded.coordinator.invalidate(first.playerUuid());
            assertTrue(bounded.coordinator.request(third), "the next tick may retry once there is room");
        } finally {
            bounded.close();
        }
    }

    @Test
    void shutdownRevokesInFlightWorkAndKeepsRefusingWithTheBaselineIntact() {
        Candidate inFlight = fixture.candidate();
        fixture.coordinator.request(inFlight);

        fixture.coordinator.shutdown();
        fixture.journal.answerRead("PICKUP");
        fixture.drainMainThread();

        assertTrue(fixture.journal.writes.isEmpty(),
            "a disabled coordinator must not finish an in-flight loss: " + fixture.journal.writes);

        Candidate afterwards = fixture.candidate();
        assertFalse(fixture.coordinator.request(afterwards), "no work is accepted after shutdown");
        assertEquals(1, afterwards.rearmCount(), "a refusal at shutdown still hands the code back");
    }

    /** Runs an action to completion on another thread, so the race is real and still deterministic. */
    private static void onAnotherThread(Runnable action) {
        Thread thread = new Thread(action, "itemguard-fence-test");
        thread.start();
        try {
            thread.join(5_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the racing thread", interrupted);
        }
        if (thread.isAlive()) {
            throw new IllegalStateException("the racing thread did not finish");
        }
    }

    /** The coordinator with a hand-driven journal and dispatcher. */
    private static final class Fixture {

        final HandJournal journal = new HandJournal();
        final Deque<Runnable> mainThread = new ArrayDeque<>();
        final AtomicBoolean enabled = new AtomicBoolean(true);
        final AtomicLong clock = new AtomicLong(1_000L);
        final ItemLossScanOffloadCoordinator coordinator;

        Fixture() {
            this(ItemLossScanOffloadCoordinator.DEFAULT_MAX_PENDING);
        }

        Fixture(int maxPending) {
            this.coordinator = new ItemLossScanOffloadCoordinator(
                journal, mainThread::add, enabled::get,
                new ScanEpochGenerator(clock::incrementAndGet), maxPending);
        }

        Candidate candidate() {
            return candidate(CODE);
        }

        Candidate candidate(String code) {
            UUID player = UUID.randomUUID();
            return new Candidate(code, UUID.randomUUID(), player, coordinator.beginGeneration(player));
        }

        void drainMainThread() {
            for (Runnable task = mainThread.poll(); task != null; task = mainThread.poll()) {
                task.run();
            }
        }

        void close() {
            coordinator.shutdown();
        }
    }

    /**
     * A journal that answers only when the test says so, and that can run an action at the exact
     * moment a write is submitted.
     */
    private static final class HandJournal implements LossJournal {

        final List<LossRecord> writes = Collections.synchronizedList(new ArrayList<>());

        private final Deque<CompletableFuture<String>> pendingReads = new ArrayDeque<>();
        private final Deque<CompletableFuture<Boolean>> pendingWrites = new ArrayDeque<>();
        private final AtomicInteger readsSubmitted = new AtomicInteger();
        private volatile Runnable duringWrite = () -> {
        };

        @Override
        public CompletableFuture<String> lastRecordedAction(String code) {
            readsSubmitted.incrementAndGet();
            CompletableFuture<String> future = new CompletableFuture<>();
            pendingReads.add(future);
            return future;
        }

        @Override
        public CompletableFuture<Boolean> recordLoss(LossRecord record) {
            writes.add(record);
            // Stands in for a worker admitting the write. Everything the hook below does happens
            // after the claim, which is the side of the boundary where a revocation is too late.
            record.permit().claim();
            duringWrite.run();
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            pendingWrites.add(future);
            return future;
        }

        void duringWrite(Runnable action) {
            this.duringWrite = action;
        }

        int readsSubmitted() {
            return readsSubmitted.get();
        }

        void answerRead(String action) {
            CompletableFuture<String> future = pendingReads.poll();
            if (future == null) {
                throw new IllegalStateException("no journal read is in flight");
            }
            future.complete(action);
        }

        void answerWrite(boolean written) {
            CompletableFuture<Boolean> future = pendingWrites.poll();
            if (future == null) {
                throw new IllegalStateException("no journal write is in flight");
            }
            future.complete(written);
        }
    }

    /** A departed stack whose revalidation can be made to do something inconvenient. */
    private static final class Candidate implements LossCandidate {

        private final String code;
        private final UUID itemUuid;
        private final UUID playerUuid;
        private final long generation;
        private final UUID departureToken = UUID.randomUUID();
        private final AtomicInteger recorded = new AtomicInteger();
        private final AtomicInteger rearmed = new AtomicInteger();
        private final AtomicInteger unrecordable = new AtomicInteger();
        private volatile Runnable duringRevalidation = () -> {
        };

        Candidate(String code, UUID itemUuid, UUID playerUuid, long generation) {
            this.code = code;
            this.itemUuid = itemUuid;
            this.playerUuid = playerUuid;
            this.generation = generation;
        }

        void duringRevalidation(Runnable action) {
            this.duringRevalidation = action;
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
            duringRevalidation.run();
            // The stack really is gone: the only reason not to write is the invalidation above.
            return true;
        }

        @Override
        public void recorded() {
            recorded.incrementAndGet();
        }

        @Override
        public void rearmBaseline() {
            rearmed.incrementAndGet();
        }

        /** No fence case produces one; counted so a stray retirement here would be visible. */
        @Override
        public void unrecordable(String reason) {
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
    }
}
