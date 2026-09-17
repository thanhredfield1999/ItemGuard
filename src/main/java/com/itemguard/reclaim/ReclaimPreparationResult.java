package com.itemguard.reclaim;

import com.itemguard.snapshot.ItemSnapshot;

import java.util.Optional;

public record ReclaimPreparationResult(
    ReclaimPreparationStatus status,
    ReclaimItemRecord itemRecord,
    ItemSnapshot itemSnapshot,
    ReclaimClaim reclaimClaim
) {
    public Optional<ReclaimItemRecord> item() {
        return Optional.ofNullable(itemRecord);
    }

    public Optional<ItemSnapshot> snapshot() {
        return Optional.ofNullable(itemSnapshot);
    }

    public Optional<ReclaimClaim> claim() {
        return Optional.ofNullable(reclaimClaim);
    }
}
