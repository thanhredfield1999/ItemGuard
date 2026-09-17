package com.itemguard.reclaim;

import com.itemguard.snapshot.ItemSnapshot;

import java.util.Optional;

public record ReclaimPreflightResult(
    ReclaimPreflightStatus status,
    ItemSnapshot itemSnapshot,
    ReclaimDecision capabilityDecision
) {
    public Optional<ItemSnapshot> snapshot() {
        return Optional.ofNullable(itemSnapshot);
    }

    public Optional<ReclaimDecision> decision() {
        return Optional.ofNullable(capabilityDecision);
    }
}
