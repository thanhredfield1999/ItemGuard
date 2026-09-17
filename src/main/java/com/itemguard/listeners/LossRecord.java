package com.itemguard.listeners;

import com.itemguard.restore.LossReason;

import java.util.UUID;

/**
 * One loss as it crosses the thread boundary: identifiers and a reason, never a Bukkit object.
 *
 * <p>The generation is carried so a write can be attributed to the scan pass that approved it. It is
 * not a validity check by itself — by the time this record reaches the journal thread the decision
 * has already been committed on the main thread.
 *
 * <p>The departure token, not the generation, is what says whether two records describe the same
 * disappearance. Generations repeat after a restart; a token comes from the baseline and lives as
 * long as the code stays missing. See {@link LossCandidate#departureToken()} for the obligation that
 * puts on whoever supplies candidates.
 *
 * <p>The permit is the exception to "identifiers and a reason": it is a live handle, not data, and it
 * is what lets a queued write still be called off. See {@link LossJournal.WritePermit}.
 */
public record LossRecord(
    String code,
    UUID itemUuid,
    LossReason reason,
    UUID playerUuid,
    long generation,
    UUID departureToken,
    LossJournal.WritePermit permit,

    /**
     * Where the loss happened, formatted on the main thread when the candidate was built.
     *
     * <p>A {@code String} and not a {@code Location} on purpose. The journal thread must not touch a
     * Bukkit object, and by the time it runs the player may have moved or logged out, so the only
     * honest value is the one taken at the moment the departure was noticed. Null means nothing was
     * captured; the journal leaves whatever the row already said rather than inventing a position.
     */
    String location
) {
}
