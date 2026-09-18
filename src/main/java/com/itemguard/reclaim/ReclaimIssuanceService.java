package com.itemguard.reclaim;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The two state transitions either side of a reclaim hand-over.
 *
 * <p>Why the hand-over is bracketed rather than atomic: the item is handed over in a player
 * inventory, which is server-thread state the database cannot join, so the guarantee has to come from
 * what each failure leaves behind instead of from one transaction.
 *
 * <ul>
 *   <li>{@link #arm(ReclaimClaim)} moves PENDING → PREPARED. That transition is what makes the
 *       identity's unique index refuse every other claim while the hand-over is in flight, so an
 *       item cannot be issued twice however many commands arrive.
 *   <li>{@link #settle(ReclaimClaim, boolean, String, String)} records what actually happened:
 *       delivered → PREPARED → COMMITTED (terminal, and still inside the index, so the identity can
 *       never be claimed again), not delivered → PREPARED → DENIED (outside the index, so the player
 *       may try again — a full inventory must cost a retry, not the item).
 * </ul>
 *
 * <p>Two consequences of that ordering are deliberate and are pinned by tests. A delivery that
 * happened is recorded even when the operator switched the gate off mid-flight: the flag gates
 * <em>arming</em>, not the record of a hand-over that already occurred, because a delivered item with
 * a re-claimable record is a duplication path. And a settle that loses its race is reported as
 * {@link ReclaimIssuanceStatus#REFUSED_STALE} rather than assumed to have applied.
 */
public final class ReclaimIssuanceService {

    private static final int MAX_DETAIL_LENGTH = 256;

    private final ReclaimClaimStore store;
    private final BooleanSupplier enabled;
    private final LongSupplier clockMillis;

    public ReclaimIssuanceService(
        ReclaimClaimStore store,
        BooleanSupplier enabled,
        LongSupplier clockMillis
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** Brackets the hand-over: the claim is now the one thing stopping a second issue. */
    public ReclaimIssuanceDecision arm(ReclaimClaim claim) {
        Objects.requireNonNull(claim, "claim");
        if (!enabled.getAsBoolean()) {
            return new ReclaimIssuanceDecision(ReclaimIssuanceStatus.REFUSED_DISABLED, claim);
        }
        if (claim.state() != ReclaimClaimState.PENDING) {
            return new ReclaimIssuanceDecision(ReclaimIssuanceStatus.REFUSED_STATE, claim);
        }
        boolean moved = store.transitionReclaimClaim(
            claim.claimId(),
            ReclaimClaimState.PENDING,
            ReclaimClaimState.PREPARED,
            clockMillis.getAsLong(),
            "armed for delivery"
        );
        return new ReclaimIssuanceDecision(
            moved ? ReclaimIssuanceStatus.ARMED : ReclaimIssuanceStatus.REFUSED_STALE,
            claim
        );
    }

    /**
     * Records the outcome of a hand-over attempt.
     *
     * @param delivered true when the item is in the player's hands; that answer decides the terminal
     *                  state, and there is no third option — an unknown outcome must be reported as
     *                  not delivered (retryable) by the caller and never guessed here.
     * @param actor     who performed the hand-over, recorded in the claim detail for the audit trail.
     */
    public ReclaimIssuanceDecision settle(
        ReclaimClaim claim,
        boolean delivered,
        String actor,
        String detail
    ) {
        Objects.requireNonNull(claim, "claim");
        if (claim.state() != ReclaimClaimState.PREPARED) {
            return new ReclaimIssuanceDecision(ReclaimIssuanceStatus.REFUSED_STATE, claim);
        }
        String actorName = actor == null || actor.isBlank() ? "unknown" : actor.trim();
        String recordedDetail = boundedDetail(
            (delivered ? "issued to " : "not delivered to ") + actorName + ": " + safeDetail(detail)
        );
        ReclaimClaimState target = delivered
            ? ReclaimClaimState.COMMITTED
            : ReclaimClaimState.DENIED;
        boolean moved = store.transitionReclaimClaim(
            claim.claimId(),
            ReclaimClaimState.PREPARED,
            target,
            clockMillis.getAsLong(),
            recordedDetail
        );
        if (!moved) {
            return new ReclaimIssuanceDecision(ReclaimIssuanceStatus.REFUSED_STALE, claim);
        }
        return new ReclaimIssuanceDecision(
            delivered ? ReclaimIssuanceStatus.ISSUED : ReclaimIssuanceStatus.ABORTED_RETRYABLE,
            claim
        );
    }

    private String safeDetail(String detail) {
        return detail == null ? "" : detail.trim();
    }

    private String boundedDetail(String detail) {
        return detail.length() <= MAX_DETAIL_LENGTH
            ? detail
            : detail.substring(0, MAX_DETAIL_LENGTH);
    }
}
