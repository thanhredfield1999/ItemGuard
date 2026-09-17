package com.itemguard.reclaim;

import java.util.Optional;
import java.util.UUID;

public interface ReclaimClaimStore {
    boolean beginReclaimClaim(ReclaimClaim claim);

    boolean transitionReclaimClaim(
        UUID claimId,
        ReclaimClaimState expectedState,
        ReclaimClaimState targetState,
        long updatedAt,
        String detail
    );

    Optional<ReclaimClaim> getReclaimClaim(UUID claimId);
}
