package com.itemguard.reclaim;

import java.util.Objects;

/** The status together with the claim it is about, so a caller cannot act on a stale copy. */
public record ReclaimIssuanceDecision(ReclaimIssuanceStatus status, ReclaimClaim claim) {

    public ReclaimIssuanceDecision {
        status = Objects.requireNonNull(status, "status");
        claim = Objects.requireNonNull(claim, "claim");
    }

    public boolean issued() {
        return status == ReclaimIssuanceStatus.ISSUED;
    }
}
