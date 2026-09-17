package com.itemguard.reclaim;

import com.itemguard.snapshot.ItemSnapshot;
import com.itemguard.snapshot.ItemSnapshotCodec;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReclaimPreflightServiceTest {

    private final UUID playerUuid = UUID.randomUUID();
    private final UUID itemUuid = UUID.randomUUID();
    private final ItemSnapshot snapshot = new ItemSnapshotCodec(1024)
        .capture(new byte[] {1, 2, 3});

    @Test
    void rejectsUnknownWrongOwnerAndMissingSnapshotBeforeCapabilityScan() {
        ReclaimPreflightService unknown = service(Optional.empty(), Optional.of(snapshot), absentGate());
        ReclaimPreflightService wrongOwner = service(
            Optional.of(new ReclaimItemRecord("AB12CD", itemUuid, UUID.randomUUID(), 1_000L)),
            Optional.of(snapshot),
            absentGate()
        );
        ReclaimPreflightService missingSnapshot = service(
            Optional.of(new ReclaimItemRecord("AB12CD", itemUuid, playerUuid, 1_000L)),
            Optional.empty(),
            absentGate()
        );

        assertEquals(ReclaimPreflightStatus.NOT_TRACKED, unknown.evaluate(playerUuid, "AB12CD").status());
        assertEquals(ReclaimPreflightStatus.NOT_OWNER, wrongOwner.evaluate(playerUuid, "AB12CD").status());
        assertEquals(ReclaimPreflightStatus.SNAPSHOT_MISSING, missingSnapshot.evaluate(playerUuid, "AB12CD").status());
    }

    @Test
    void corruptSnapshotAndUnavailableCapabilityFailClosed() {
        ItemSnapshot corrupt = new ItemSnapshot(
            snapshot.version(),
            new byte[] {9, 9, 9},
            snapshot.sha256()
        );
        ReclaimPreflightService corruptService = service(
            Optional.of(new ReclaimItemRecord("AB12CD", itemUuid, playerUuid, 1_000L)),
            Optional.of(corrupt),
            absentGate()
        );
        ReclaimPreflightService unavailable = service(
            Optional.of(new ReclaimItemRecord("AB12CD", itemUuid, playerUuid, 1_000L)),
            Optional.of(snapshot),
            new ReclaimCapabilityGate(java.util.List.of(
                target -> new PresenceEvidence(
                    "PLAYER_VAULTS",
                    PresenceStatus.UNAVAILABLE,
                    "API not verified"
                )
            ))
        );

        assertEquals(ReclaimPreflightStatus.SNAPSHOT_INVALID,
            corruptService.evaluate(playerUuid, "AB12CD").status());
        assertEquals(ReclaimPreflightStatus.DENIED_CAPABILITY,
            unavailable.evaluate(playerUuid, "AB12CD").status());
    }

    @Test
    void returnsEligibleOnlyAfterSnapshotAndEveryCapabilityPass() {
        ReclaimPreflightService service = service(
            Optional.of(new ReclaimItemRecord("AB12CD", itemUuid, playerUuid, 1_000L)),
            Optional.of(snapshot),
            absentGate()
        );

        ReclaimPreflightResult result = service.evaluate(playerUuid, "AB12CD");

        assertEquals(ReclaimPreflightStatus.ELIGIBLE, result.status());
        assertEquals(snapshot, result.snapshot().orElseThrow());
    }

    private ReclaimPreflightService service(
        Optional<ReclaimItemRecord> item,
        Optional<ItemSnapshot> storedSnapshot,
        ReclaimCapabilityGate gate
    ) {
        return new ReclaimPreflightService(
            code -> item,
            code -> storedSnapshot,
            new ItemSnapshotCodec(1024),
            gate
        );
    }

    private ReclaimCapabilityGate absentGate() {
        return new ReclaimCapabilityGate(java.util.List.of(
            target -> new PresenceEvidence(
                "PLAYER_INVENTORY",
                PresenceStatus.ABSENT,
                "absent"
            )
        ));
    }
}
