package com.itemguard.reclaim;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReclaimClaimServiceTest {

    @Test
    void reserveCreatesPendingClaimWithStableIdempotencyKey() {
        FakeStore store = new FakeStore();
        UUID claimId = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        ReclaimClaimService service = new ReclaimClaimService(
            store,
            () -> claimId,
            () -> 1_000L
        );

        Optional<ReclaimClaim> reserved = service.reserve(playerUuid, "ab12cd");

        ReclaimClaim claim = reserved.orElseThrow();
        assertEquals(claimId, claim.claimId());
        assertEquals(claimId.toString(), claim.idempotencyKey());
        assertEquals("AB12CD", claim.code());
        assertEquals(ReclaimClaimState.PENDING, claim.state());
        assertEquals(claim, store.claims.get(claimId));
    }

    @Test
    void reserveReturnsEmptyWhenIdentityIsAlreadyLocked() {
        FakeStore store = new FakeStore();
        store.acceptReservations = false;
        ReclaimClaimService service = new ReclaimClaimService(
            store,
            UUID::randomUUID,
            () -> 1_000L
        );

        assertTrue(service.reserve(UUID.randomUUID(), "AB12CD").isEmpty());
    }

    @Test
    void denyUsesPendingCasAndPersistsBoundedDetail() {
        FakeStore store = new FakeStore();
        UUID claimId = UUID.randomUUID();
        ReclaimClaimService service = new ReclaimClaimService(
            store,
            () -> claimId,
            () -> 2_000L
        );
        ReclaimClaim claim = service.reserve(UUID.randomUUID(), "AB12CD").orElseThrow();
        String oversized = "X".repeat(600);

        assertTrue(service.deny(claim.claimId(), oversized));
        assertEquals(ReclaimClaimState.PENDING, store.lastExpected);
        assertEquals(ReclaimClaimState.DENIED, store.lastTarget);
        assertEquals(256, store.lastDetail.length());
        assertFalse(service.deny(claim.claimId(), "again"));
    }

    private static final class FakeStore implements ReclaimClaimStore {
        private final Map<UUID, ReclaimClaim> claims = new HashMap<>();
        private boolean acceptReservations = true;
        private ReclaimClaimState lastExpected;
        private ReclaimClaimState lastTarget;
        private String lastDetail;

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
            ReclaimClaim current = claims.get(claimId);
            if (current == null || current.state() != expectedState) {
                return false;
            }
            lastExpected = expectedState;
            lastTarget = targetState;
            lastDetail = detail;
            claims.put(claimId, new ReclaimClaim(
                current.claimId(),
                current.idempotencyKey(),
                current.playerUuid(),
                current.code(),
                targetState,
                current.requestedAt(),
                updatedAt,
                detail
            ));
            return true;
        }

        @Override
        public Optional<ReclaimClaim> getReclaimClaim(UUID claimId) {
            return Optional.ofNullable(claims.get(claimId));
        }
    }
}
