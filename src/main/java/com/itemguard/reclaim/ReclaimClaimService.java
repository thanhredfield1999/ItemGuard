package com.itemguard.reclaim;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class ReclaimClaimService {

    private static final int MAX_DETAIL_LENGTH = 256;

    private final ReclaimClaimStore store;
    private final Supplier<UUID> claimIds;
    private final LongSupplier clockMillis;

    public ReclaimClaimService(
        ReclaimClaimStore store,
        Supplier<UUID> claimIds,
        LongSupplier clockMillis
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.claimIds = Objects.requireNonNull(claimIds, "claimIds");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    public Optional<ReclaimClaim> reserve(UUID playerUuid, String code) {
        UUID claimId = Objects.requireNonNull(claimIds.get(), "claimId");
        long now = clockMillis.getAsLong();
        ReclaimClaim claim = new ReclaimClaim(
            claimId,
            claimId.toString(),
            Objects.requireNonNull(playerUuid, "playerUuid"),
            normalizeCode(code),
            ReclaimClaimState.PENDING,
            now,
            now,
            null
        );
        return store.beginReclaimClaim(claim)
            ? Optional.of(claim)
            : Optional.empty();
    }

    public boolean deny(UUID claimId, String detail) {
        return store.transitionReclaimClaim(
            Objects.requireNonNull(claimId, "claimId"),
            ReclaimClaimState.PENDING,
            ReclaimClaimState.DENIED,
            clockMillis.getAsLong(),
            boundedDetail(detail)
        );
    }

    private String normalizeCode(String code) {
        String normalized = Objects.requireNonNull(code, "code")
            .trim()
            .toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Reclaim code is required");
        }
        return normalized;
    }

    private String boundedDetail(String detail) {
        String normalized = detail == null ? "" : detail.trim();
        return normalized.length() <= MAX_DETAIL_LENGTH
            ? normalized
            : normalized.substring(0, MAX_DETAIL_LENGTH);
    }
}
