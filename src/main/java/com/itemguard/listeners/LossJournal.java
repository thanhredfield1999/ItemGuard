package com.itemguard.listeners;

import java.util.concurrent.CompletableFuture;

/**
 * The history side of a loss scan, with both the read and the write off the calling thread.
 *
 * <p>The scan runs on a repeating main-thread task, so a synchronous JDBC read followed by two
 * synchronous writes is three disk waits inside a tick. Returning futures is the whole point of the
 * seam: an implementation serialises the work through the existing
 * {@code com.itemguard.persistence.SerialDatabaseExecutor} and answers later.
 *
 * <p>An implementation may refuse work by throwing {@link java.util.concurrent.RejectedExecutionException}
 * — a full queue or a shut-down executor is a normal outcome during disable, not a bug — and the
 * coordinator treats that as "not now, try again next tick".
 */
public interface LossJournal {

    /**
     * The most recent action recorded for a code, or null when nothing is on record.
     *
     * <p>Completing exceptionally (a timeout, a closed connection) is a refusal, not a "no history":
     * the difference decides whether a baseline is retired or rearmed.
     */
    CompletableFuture<String> lastRecordedAction(String code);

    /**
     * Writes the loss. Completes with false when the write was not applied.
     *
     * <p>An implementation must claim {@link LossRecord#permit()} on the worker that performs the
     * write, immediately before it mutates anything, and must write nothing when the claim fails.
     * Submitting to a queue is not a commitment: the main thread goes on running while the write
     * waits, and the stack can be seen again in that gap.
     */
    CompletableFuture<Boolean> recordLoss(LossRecord record);

    /**
     * The code has no tracked item behind it, so there is nothing to record and nothing to retry.
     *
     * <p>Distinct from both answers {@code recordLoss} already has. False means "refused, the next
     * attempt may succeed" — a revoked permit, an explained departure — and the coordinator rearms
     * the baseline on it. A pruned {@code tracked_items} row is not that: the PDC tag on the item
     * keeps the code alive while the row it refers to is gone for good, so rearming schedules the
     * identical attempt once per scan forever. True is not available either — it would log a loss
     * and release the departure as though a row had been written.
     */
    final class UntrackedCodeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UntrackedCodeException(String code) {
            super("no tracked item carries code " + code);
        }
    }

    /**
     * The right to perform one queued write, revocable until the worker takes it.
     *
     * <p>This is the linearisation point for a decision that spans two threads. The coordinator
     * approves a loss on the main thread and cannot hold that approval still while the write sits in
     * a queue — a sighting of the same stack can arrive in between, and the database fence cannot see
     * it, because "the item is back in the inventory" appends no row for the fence to read. The permit
     * moves the final word to the worker: whoever gets there first wins, exactly once.
     *
     * <p>Claiming is one-shot and thread-safe. It is called from a journal thread and raced against
     * revocations made on the main thread, so it must touch nothing but its own state — never a Bukkit
     * object, never the candidate.
     */
    @FunctionalInterface
    interface WritePermit {

        /**
         * Takes the right to write, if it is still there.
         *
         * @return true exactly once, and only while the decision still stands; false once it has been
         *     revoked or already claimed
         */
        boolean claim();
    }
}
