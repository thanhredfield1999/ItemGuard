package com.itemguard.custody;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Who holds a tracked item, who has held it before, and how often it genuinely changed hands.
 *
 * <p>Immutable: every observation produces a new state, so a rejected observation can safely return
 * the previous instance unchanged.
 *
 * @param holder          the player currently known to hold it, or null before the first observation
 * @param knownHolders    distinct players who have held it, in the order they first did
 * @param transfers       genuine handovers, excluding self drop/pickup and throttled ping-pong
 * @param lastObservedAt  event time of the last accepted observation
 * @param lastCountedFrom the giver of the last counted handover
 * @param lastCountedTo   the receiver of the last counted handover
 * @param lastCountedAt   event time of the last counted handover
 */
public record CustodyState(
    UUID holder,
    List<UUID> knownHolders,
    int transfers,
    long lastObservedAt,
    UUID lastCountedFrom,
    UUID lastCountedTo,
    long lastCountedAt
) {

    public CustodyState {
        knownHolders = knownHolders == null ? List.of() : List.copyOf(knownHolders);
    }

    public static CustodyState empty() {
        return new CustodyState(null, List.of(), 0, Long.MIN_VALUE, null, null, Long.MIN_VALUE);
    }

    /** Number of different players known to have held this item. Cannot be farmed by two accounts. */
    public int distinctHolders() {
        return knownHolders.size();
    }

    public boolean hasHeld(UUID player) {
        return knownHolders.contains(player);
    }

    CustodyState withHolder(UUID next, long observedAt, boolean counted, UUID from) {
        Objects.requireNonNull(next, "next");
        List<UUID> chain = knownHolders;
        if (!chain.contains(next)) {
            List<UUID> extended = new ArrayList<>(chain);
            extended.add(next);
            chain = List.copyOf(extended);
        }
        return new CustodyState(
            next,
            chain,
            counted ? transfers + 1 : transfers,
            observedAt,
            counted ? from : lastCountedFrom,
            counted ? next : lastCountedTo,
            counted ? observedAt : lastCountedAt);
    }
}
