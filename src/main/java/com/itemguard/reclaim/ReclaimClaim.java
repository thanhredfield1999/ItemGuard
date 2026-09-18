package com.itemguard.reclaim;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record ReclaimClaim(
    UUID claimId,
    String idempotencyKey,
    UUID playerUuid,
    String code,
    ReclaimClaimState state,
    long requestedAt,
    long updatedAt,
    String detail
) {
    /**
     * This claim as it will be after a transition the store has just applied.
     *
     * <p>Exists because of a bug the runtime gate found on 2026-09-19: the hand-over flow armed a
     * claim (PENDING -> PREPARED) and then settled using the record it had been holding, whose
     * {@code state} still said PENDING. Settling refuses anything that is not PREPARED, so every
     * issuance armed the claim, delivered the item, wrote nothing, and left the identity locked in
     * PREPARED for ever — while telling the player it had succeeded. Unit tests missed it because
     * they handed {@code settle} a PREPARED record directly. The moved copy is what a caller must
     * carry forward.
     */
    public ReclaimClaim movedTo(ReclaimClaimState target, long updatedAt, String detail) {
        return new ReclaimClaim(
            claimId, idempotencyKey, playerUuid, code, target, requestedAt, updatedAt, detail
        );
    }

    public ReclaimClaim {
        claimId = Objects.requireNonNull(claimId, "claimId");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").trim();
        playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        code = Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT);
        state = Objects.requireNonNull(state, "state");
        if (idempotencyKey.isEmpty() || code.isEmpty()) {
            throw new IllegalArgumentException("Claim idempotency key and code are required");
        }
        if (updatedAt < requestedAt) {
            throw new IllegalArgumentException("Claim update cannot precede request");
        }
    }
}
