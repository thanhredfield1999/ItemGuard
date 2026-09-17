package com.itemguard.multiserver;

import java.util.Objects;
import java.util.UUID;

/**
 * One row of "this identity was seen on that server at that time" — the only input the
 * cross-server rule has, and the only input it is allowed to want.
 *
 * <p>It is a type of its own rather than a widened {@link com.itemguard.dupe.ItemObservation}:
 * observations are the single-server vocabulary and their shape is what the shipped, verified
 * epoch rule is written against. Widening that record would change a shared type so that a
 * Premium-only column could travel in it.
 *
 * @param itemUuid  the identity that was seen
 * @param serverId  the configured name of the server that saw it
 * @param observedAt when the sighting was recorded
 * @param expired   whether the record has aged out of retention; an expired sighting is not
 *                  evidence of anything current and is ignored by the rule
 */
public record CrossServerSighting(UUID itemUuid, String serverId, long observedAt, boolean expired) {

    public CrossServerSighting {
        Objects.requireNonNull(itemUuid, "itemUuid");
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("A sighting needs a server name");
        }
        if (observedAt < 0) {
            throw new IllegalArgumentException("observedAt must not be negative");
        }
    }
}
