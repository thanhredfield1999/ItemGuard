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
