package com.itemguard.listeners;

import com.itemguard.dupe.ScanEpochGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the coordinator does with an answer that will never change.
 *
 * <p>Every unsuccessful write is treated the same way: rearm the baseline so the next scan tries
 * again. That is right for a refusal — a full queue, a revoked permit, a timeout — because the next
 * attempt can succeed. It is wrong for a code whose {@code tracked_items} row was pruned: the tag on
 * the item keeps the code alive, the row is never coming back, and rearming schedules the identical
 * attempt forever at one per scan.
 *
 * <p>Retiring it must not look like success. {@code recorded()} logs a loss and releases the
 * departure token as though a row exists; for a code with nothing to clear, no row was written and
 * saying otherwise would put a loss in the log that never happened.
 */
class ItemLossScanOffloadLivelockTest {

    private static final String CODE = "ABC123";

    @Test
    void anUntrackedCodeIsRetiredInsteadOfRetriedForever() {
        Fixture fixture = new Fixture();
        fixture.journal.failWriteWith = new LossJournal.UntrackedCodeException(CODE);
        Candidate candidate = fixture.submit();

        assertEquals(0, candidate.rearms.get(),
            "an answer that can never change was scheduled for another attempt");
        assertEquals(0, candidate.recorded.get(),
            "a code with nothing to clear was logged as a recorded loss");
        assertEquals(1, candidate.unrecordable.get(),
            "the candidate was neither retired nor retried");
    }

    @Test
    void anUntrackedCodeIsNotResubmittedByLaterScans() {
        // The livelock itself: the same code, pass after pass. Once retired it must stop coming
        // back, so the journal sees exactly one write attempt however many passes run.
        Fixture fixture = new Fixture();
        fixture.journal.failWriteWith = new LossJournal.UntrackedCodeException(CODE);

        Candidate candidate = fixture.submit();
        for (int pass = 0; pass < 5; pass++) {
            if (candidate.rearms.get() == 0) {
                break;
            }
            fixture.resubmit(candidate);
        }

        assertEquals(1, fixture.journal.writes.get(),
            "an untracked code was submitted to the journal again after being retired");
    }

    @Test
    void aRefusedWriteIsStillRetried() {
        // The control. Only the permanent answer changes: a revoked permit or a full queue answers
        // false, and that code must still come back to the baseline for the next pass.
        Fixture fixture = new Fixture();
        fixture.journal.answer = Boolean.FALSE;
        Candidate candidate = fixture.submit();

        assertEquals(1, candidate.rearms.get(), "a refused write must still rearm the baseline");
        assertEquals(0, candidate.unrecordable.get(), "a refusal is not an unrecordable code");
        assertEquals(0, candidate.recorded.get(), "nothing was recorded");
    }

    @Test
    void aRealWriteFailureIsStillRetried() {
        // A closed connection or a SQL error is not a verdict about the code. Swallowing it as
        // "unrecordable" would hide a broken database behind a tidy-looking retirement.
        Fixture fixture = new Fixture();
        fixture.journal.failWriteWith = new IllegalStateException("connection closed");
        Candidate candidate = fixture.submit();

        assertEquals(1, candidate.rearms.get(), "a real write failure must still rearm the baseline");
        assertEquals(0, candidate.unrecordable.get(), "a real failure was misread as unrecordable");
    }

    /** Drives the coordinator inline: the dispatcher runs here and the journal answers at once. */
    private static final class Fixture {

        private final StubJournal journal = new StubJournal();
        private final ItemLossScanOffloadCoordinator coordinator = new ItemLossScanOffloadCoordinator(
            journal, Runnable::run, () -> true, new ScanEpochGenerator());

        private Candidate submit() {
            Candidate candidate = new Candidate(coordinator.beginGeneration(Candidate.PLAYER));
            coordinator.request(candidate);
            return candidate;
        }

        /** What the next scan pass does with a code the baseline still says is missing. */
        private void resubmit(Candidate candidate) {
            candidate.generation = coordinator.beginGeneration(Candidate.PLAYER);
            candidate.rearms.set(0);
            coordinator.request(candidate);
        }
    }

    /** Answers immediately, so the whole request/read/write/finish path runs on this thread. */
    private static final class StubJournal implements LossJournal {

        private final AtomicInteger writes = new AtomicInteger();
        private Boolean answer = Boolean.TRUE;
        private Throwable failWriteWith;

        @Override
        public CompletableFuture<String> lastRecordedAction(String code) {
            return CompletableFuture.completedFuture("PICKUP");
        }

        @Override
        public CompletableFuture<Boolean> recordLoss(LossRecord record) {
            writes.incrementAndGet();
            record.permit().claim();
            if (failWriteWith != null) {
                return CompletableFuture.failedFuture(failWriteWith);
            }
            return CompletableFuture.completedFuture(answer);
        }
    }

    private static final class Candidate implements LossCandidate {

        private static final UUID PLAYER = UUID.randomUUID();

        private final UUID itemUuid = UUID.randomUUID();
        private final UUID departureToken = UUID.randomUUID();
        private final AtomicInteger rearms = new AtomicInteger();
        private final AtomicInteger recorded = new AtomicInteger();
        private final AtomicInteger unrecordable = new AtomicInteger();
        private final List<String> reasons = new ArrayList<>();
        private long generation;

        private Candidate(long generation) {
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
            return PLAYER;
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
            recorded.incrementAndGet();
        }

        @Override
        public void rearmBaseline() {
            rearms.incrementAndGet();
        }

        @Override
        public void unrecordable(String reason) {
            unrecordable.incrementAndGet();
            reasons.add(reason);
        }
    }
}
