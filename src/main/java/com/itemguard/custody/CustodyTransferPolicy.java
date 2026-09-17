package com.itemguard.custody;

import java.util.Objects;
import java.util.UUID;

/**
 * Decides when an item genuinely changed hands.
 *
 * <p>The counted number answers "how often did this item move between players", which is only
 * meaningful if it cannot be inflated by a player acting alone or by two accounts colluding:
 *
 * <ul>
 *   <li>Dropping and re-picking your own item keeps the same holder, so it is never counted.</li>
 *   <li>A handover to a different player is counted, and the previous holder stays in the chain, so
 *       an item remembers everyone who carried it.</li>
 *   <li>The same pair swapping repeatedly still moves custody, so the current holder is always
 *       correct, but the count is throttled for a configured window.</li>
 * </ul>
 *
 * <p>This class is pure: it performs no I/O and reads no clock, so callers supply the event time and
 * the result is fully reproducible in tests.
 */
public final class CustodyTransferPolicy {

    private final long pairCooldownMillis;

    public CustodyTransferPolicy(long pairCooldownMillis) {
        if (pairCooldownMillis < 0) {
            throw new IllegalArgumentException("pairCooldownMillis must not be negative");
        }
        this.pairCooldownMillis = pairCooldownMillis;
    }

    public long pairCooldownMillis() {
        return pairCooldownMillis;
    }

    /**
     * Records that {@code actor} is now holding the item at {@code observedAt}.
     *
     * @param state    current custody state, never null
     * @param actor    the player now holding it, or null when the movement is not attributable
     * @param observedAt event time in epoch milliseconds
     */
    public CustodyDecision observe(CustodyState state, UUID actor, long observedAt) {
        Objects.requireNonNull(state, "state");
        if (actor == null) {
            return new CustodyDecision(CustodyOutcome.NO_ACTOR, state);
        }
        if (state.holder() == null) {
            return new CustodyDecision(CustodyOutcome.FIRST_HOLDER,
                state.withHolder(actor, observedAt, false, null));
        }
        if (observedAt < state.lastObservedAt()) {
            return new CustodyDecision(CustodyOutcome.STALE_EVENT, state);
        }
        UUID previous = state.holder();
        if (previous.equals(actor)) {
            return new CustodyDecision(CustodyOutcome.SAME_HOLDER,
                state.withHolder(actor, observedAt, false, null));
        }
        if (throttled(state, previous, actor, observedAt)) {
            return new CustodyDecision(CustodyOutcome.TRANSFER_THROTTLED,
                state.withHolder(actor, observedAt, false, null));
        }
        return new CustodyDecision(CustodyOutcome.TRANSFER,
            state.withHolder(actor, observedAt, true, previous));
    }

    /**
     * True when this handover involves the same two players as the last counted one and happened
     * inside the cooldown window. Direction is ignored, so A to B and B to A are the same pair.
     */
    private boolean throttled(CustodyState state, UUID previous, UUID actor, long observedAt) {
        if (pairCooldownMillis == 0 || state.lastCountedFrom() == null || state.lastCountedTo() == null) {
            return false;
        }
        if (!samePair(state.lastCountedFrom(), state.lastCountedTo(), previous, actor)) {
            return false;
        }
        return observedAt - state.lastCountedAt() < pairCooldownMillis;
    }

    private boolean samePair(UUID lastFrom, UUID lastTo, UUID from, UUID to) {
        return (lastFrom.equals(from) && lastTo.equals(to))
            || (lastFrom.equals(to) && lastTo.equals(from));
    }
}
