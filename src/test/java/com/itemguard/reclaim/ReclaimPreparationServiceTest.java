package com.itemguard.reclaim;

import com.itemguard.snapshot.ItemSnapshot;
import com.itemguard.snapshot.ItemSnapshotCodec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReclaimPreparationServiceTest {

    private final UUID playerUuid = UUID.randomUUID();
    private final UUID itemUuid = UUID.randomUUID();
    private final ItemSnapshotCodec codec = new ItemSnapshotCodec(1024);
    private final ItemSnapshot snapshot = codec.capture(new byte[] {1, 2, 3});

    @Test
    void rejectsInvalidDataBeforeCreatingClaim() {
        FakeClaimStore claims = new FakeClaimStore();
        ReclaimClaimService claimService = claimService(claims);
        ReclaimPreparationService unknown = service(
            Optional.empty(),
            Optional.of(snapshot),
            claimService
        );
        ReclaimPreparationService wrongOwner = service(
            Optional.of(record(UUID.randomUUID())),
            Optional.of(snapshot),
            claimService
        );
        ReclaimPreparationService missing = service(
            Optional.of(record(playerUuid)),
            Optional.empty(),
            claimService
        );
        ItemSnapshot corrupt = new ItemSnapshot(
            snapshot.version(),
            new byte[] {9, 9, 9},
            snapshot.sha256()
        );
        ReclaimPreparationService invalid = service(
            Optional.of(record(playerUuid)),
            Optional.of(corrupt),
            claimService
        );

        assertEquals(ReclaimPreparationStatus.NOT_TRACKED,
            unknown.prepare(playerUuid, "AB12CD").status());
        assertEquals(ReclaimPreparationStatus.NOT_OWNER,
            wrongOwner.prepare(playerUuid, "AB12CD").status());
        assertEquals(ReclaimPreparationStatus.SNAPSHOT_MISSING,
            missing.prepare(playerUuid, "AB12CD").status());
        assertEquals(ReclaimPreparationStatus.SNAPSHOT_INVALID,
            invalid.prepare(playerUuid, "AB12CD").status());
        assertTrue(claims.claims.isEmpty());
    }

    @Test
    void validDataReservesPendingClaimAndReturnsImmutableEvidence() {
        FakeClaimStore claims = new FakeClaimStore();
        ReclaimPreparationService service = service(
            Optional.of(record(playerUuid)),
            Optional.of(snapshot),
            claimService(claims)
        );

        ReclaimPreparationResult result = service.prepare(playerUuid, "ab12cd");

        assertEquals(ReclaimPreparationStatus.RESERVED, result.status());
        assertEquals(itemUuid, result.item().orElseThrow().itemUuid());
        assertEquals(snapshot, result.snapshot().orElseThrow());
        assertEquals(ReclaimClaimState.PENDING,
            result.claim().orElseThrow().state());
    }

    @Test
    void activeOrCommittedIdentityReturnsConflict() {
        FakeClaimStore claims = new FakeClaimStore();
        claims.acceptReservations = false;
        ReclaimPreparationService service = service(
            Optional.of(record(playerUuid)),
            Optional.of(snapshot),
            claimService(claims)
        );

        assertEquals(
            ReclaimPreparationStatus.CLAIM_CONFLICT,
            service.prepare(playerUuid, "AB12CD").status()
        );
    }

    private ReclaimPreparationService service(
        Optional<ReclaimItemRecord> item,
        Optional<ItemSnapshot> storedSnapshot,
        ReclaimClaimService claims
    ) {
        return new ReclaimPreparationService(
            code -> item,
            code -> storedSnapshot,
            codec,
            claims
        );
    }

    private ReclaimClaimService claimService(FakeClaimStore store) {
        return new ReclaimClaimService(
            store,
            UUID::randomUUID,
            () -> 1_000L
        );
    }

    private ReclaimItemRecord record(UUID ownerUuid) {
        return new ReclaimItemRecord("AB12CD", itemUuid, ownerUuid, 500L);
    }

    private static final class FakeClaimStore implements ReclaimClaimStore {
        private final Map<UUID, ReclaimClaim> claims = new HashMap<>();
        private boolean acceptReservations = true;

        @Override
        public boolean beginReclaimClaim(ReclaimClaim claim) {
            if (!acceptReservations) {
                return false;
            }
            claims.put(claim.claimId(), claim);
            return true;
        }

        @Override
        public boolean transitionReclaimClaim(
            UUID claimId,
            ReclaimClaimState expectedState,
            ReclaimClaimState targetState,
            long updatedAt,
            String detail
        ) {
            return false;
        }

        @Override
        public Optional<ReclaimClaim> getReclaimClaim(UUID claimId) {
            return Optional.ofNullable(claims.get(claimId));
        }
    }
}
