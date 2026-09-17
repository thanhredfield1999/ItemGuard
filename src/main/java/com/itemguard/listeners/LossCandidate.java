package com.itemguard.listeners;

import java.util.UUID;

/**
 * A stack a scan pass believes has gone missing.
 *
 * <p>Every method reads or writes listener state that is only safe on the main thread, so the
 * coordinator never calls one of them from a journal thread — it snapshots the four identifying
 * values at request time and hops back to the main thread for the rest.
 */
public interface LossCandidate {

    String code();

    UUID itemUuid();

    UUID playerUuid();

    /** The scan generation this candidate belongs to, from {@code beginGeneration}. */
    long generation();

    /**
     * Identifies the disappearance itself, so a loss written twice can be recognised as one loss.
     *
     * <p><b>This is a contract on the implementer, and the journal's correctness rests on it.</b> The
     * same token must be returned for as long as the code has been continuously missing, and a
     * different one only after the code has been seen again. The generation cannot serve: it is
     * reissued by a fresh {@code ScanEpochGenerator} after a restart, and a genuinely new departure
     * that happened to draw a reused generation would be silently discarded as a replay.
     *
     * <p>The lifetime that matters is the baseline's, not any one request's. A write whose outcome is
     * ambiguous — committed, then the answer lost — rearms the baseline, and the next pass submits the
     * same disappearance again through a new candidate object. If the token is minted per candidate,
     * per request or per in-flight entry, that resubmission looks new and the loss is recorded twice.
     * Mint it where the baseline records that the code went missing, and drop it when the code is seen.
     *
     * <p>Uniqueness is probabilistic: a random UUID is not a proof of distinctness, only a collision
     * probability small enough to ignore next to the failure modes it replaces.
     */
    UUID departureToken();

    /**
     * Where the departure was noticed, already formatted.
     *
     * <p>Read once, on the main thread, when the candidate is built. A {@code Location} cannot cross
     * to a journal thread and would be stale by the time it got there — the player moves, or logs
     * out. Null when no position could be taken, which the journal treats as "leave the recorded
     * position alone" rather than as an unknown to write down.
     */
    String location();

    /**
     * Re-checks the inventory on the main thread once the journal has answered.
     *
     * <p>The answer arrives a few ticks after the sighting; by then the stack may be back.
     */
    boolean stillMissing();

    /** The loss is on record. Called at most once per accepted request. */
    void recorded();

    /**
     * Puts the code back in the baseline after giving up.
     *
     * <p>A refused or timed-out journal call must not retire the code from {@code lastSeen}: that
     * would make the loss invisible to every later scan. Rearming makes the next tick try again.
     */
    void rearmBaseline();

    /**
     * Retires the code because no attempt can ever record it, without claiming one did.
     *
     * <p>The middle answer {@link #recorded()} and {@link #rearmBaseline()} leave out. Rearming a
     * code whose tracked row was pruned repeats the same doomed write once per scan for as long as
     * the item carries its tag; calling {@code recorded()} would log a loss and release the
     * departure as though a row existed. This retires the baseline entry and says why, in wording
     * that cannot be mistaken for a recorded loss.
     */
    void unrecordable(String reason);
}
