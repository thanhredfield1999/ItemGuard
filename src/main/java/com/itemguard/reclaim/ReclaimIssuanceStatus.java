package com.itemguard.reclaim;

/**
 * What {@link ReclaimIssuanceService} decided.
 *
 * <p>{@code REFUSED_STALE} exists because "the write did not apply" and "the write was not attempted"
 * are different facts: the first means another writer moved the claim, the second means this caller
 * asked for something the state does not allow. Reporting one as the other is how a lost race turns
 * into an unrecorded hand-over.
 */
public enum ReclaimIssuanceStatus {
    /** The claim is now PREPARED: the identity is locked for this hand-over. */
    ARMED,
    /** The item was handed over and the claim is COMMITTED, permanently. */
    ISSUED,
    /** The hand-over did not happen; the claim is DENIED and the player may try again. */
    ABORTED_RETRYABLE,
    /** {@code reclaim.issuance-enabled} is off. */
    REFUSED_DISABLED,
    /** The claim is not in the state this step requires. */
    REFUSED_STATE,
    /** The transition lost a race with another writer, so nothing can be assumed about the record. */
    REFUSED_STALE
}
