package com.itemguard.reclaim;

import com.itemguard.snapshot.ItemSnapshot;
import com.itemguard.snapshot.ItemSnapshotCodec;
import com.itemguard.snapshot.SnapshotValidationException;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ReclaimPreparationService {

    private final ReclaimItemLookup items;
    private final ReclaimSnapshotLookup snapshots;
    private final ItemSnapshotCodec snapshotCodec;
    private final ReclaimClaimService claims;

    public ReclaimPreparationService(
        ReclaimItemLookup items,
        ReclaimSnapshotLookup snapshots,
        ItemSnapshotCodec snapshotCodec,
        ReclaimClaimService claims
    ) {
        this.items = Objects.requireNonNull(items, "items");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshotCodec");
        this.claims = Objects.requireNonNull(claims, "claims");
    }

    public ReclaimPreparationResult prepare(UUID playerUuid, String code) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        String normalizedCode = normalizeCode(code);
        Optional<ReclaimItemRecord> itemResult = items.find(normalizedCode);
        if (itemResult.isEmpty()) {
            return result(ReclaimPreparationStatus.NOT_TRACKED);
        }
        ReclaimItemRecord item = itemResult.orElseThrow();
        if (!playerUuid.equals(item.ownerUuid())) {
            return result(ReclaimPreparationStatus.NOT_OWNER);
        }

        Optional<ItemSnapshot> snapshotResult = snapshots.find(normalizedCode);
        if (snapshotResult.isEmpty()) {
            return result(ReclaimPreparationStatus.SNAPSHOT_MISSING);
        }
        ItemSnapshot snapshot = snapshotResult.orElseThrow();
        try {
            snapshotCodec.restore(snapshot);
        } catch (SnapshotValidationException invalid) {
            return result(ReclaimPreparationStatus.SNAPSHOT_INVALID);
        }

        Optional<ReclaimClaim> claim = claims.reserve(playerUuid, normalizedCode);
        if (claim.isEmpty()) {
            return result(ReclaimPreparationStatus.CLAIM_CONFLICT);
        }
        return new ReclaimPreparationResult(
            ReclaimPreparationStatus.RESERVED,
            item,
            snapshot,
            claim.orElseThrow()
        );
    }

    private ReclaimPreparationResult result(ReclaimPreparationStatus status) {
        return new ReclaimPreparationResult(status, null, null, null);
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
}
