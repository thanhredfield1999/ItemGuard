package com.itemguard.reclaim;

import com.itemguard.snapshot.ItemSnapshot;
import com.itemguard.snapshot.ItemSnapshotCodec;
import com.itemguard.snapshot.SnapshotValidationException;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ReclaimPreflightService {

    private final ReclaimItemLookup items;
    private final ReclaimSnapshotLookup snapshots;
    private final ItemSnapshotCodec snapshotCodec;
    private final ReclaimCapabilityGate capabilityGate;

    public ReclaimPreflightService(
        ReclaimItemLookup items,
        ReclaimSnapshotLookup snapshots,
        ItemSnapshotCodec snapshotCodec,
        ReclaimCapabilityGate capabilityGate
    ) {
        this.items = Objects.requireNonNull(items, "items");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshotCodec");
        this.capabilityGate = Objects.requireNonNull(capabilityGate, "capabilityGate");
    }

    public ReclaimPreflightResult evaluate(UUID playerUuid, String code) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        String normalizedCode = normalizeCode(code);
        Optional<ReclaimItemRecord> itemResult = items.find(normalizedCode);
        if (itemResult.isEmpty()) {
            return result(ReclaimPreflightStatus.NOT_TRACKED);
        }
        ReclaimItemRecord item = itemResult.orElseThrow();
        if (!playerUuid.equals(item.ownerUuid())) {
            return result(ReclaimPreflightStatus.NOT_OWNER);
        }

        Optional<ItemSnapshot> snapshotResult = snapshots.find(normalizedCode);
        if (snapshotResult.isEmpty()) {
            return result(ReclaimPreflightStatus.SNAPSHOT_MISSING);
        }
        ItemSnapshot snapshot = snapshotResult.orElseThrow();
        try {
            snapshotCodec.restore(snapshot);
        } catch (SnapshotValidationException invalidSnapshot) {
            return result(ReclaimPreflightStatus.SNAPSHOT_INVALID);
        }

        ReclaimDecision decision = capabilityGate.evaluate(new ReclaimTarget(
            playerUuid,
            normalizedCode,
            item.itemUuid()
        ));
        if (decision.status() != ReclaimDecisionStatus.ELIGIBLE) {
            return new ReclaimPreflightResult(
                ReclaimPreflightStatus.DENIED_CAPABILITY,
                null,
                decision
            );
        }
        return new ReclaimPreflightResult(
            ReclaimPreflightStatus.ELIGIBLE,
            snapshot,
            decision
        );
    }

    private ReclaimPreflightResult result(ReclaimPreflightStatus status) {
        return new ReclaimPreflightResult(status, null, null);
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
