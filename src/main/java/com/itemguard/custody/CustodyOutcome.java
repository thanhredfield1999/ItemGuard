package com.itemguard.custody;

/** Why a custody observation did or did not change the transfer count. */
public enum CustodyOutcome {

    /** The item had no recorded holder; this player starts the chain. Not counted as a transfer. */
    FIRST_HOLDER,

    /** The same player is holding it again, e.g. dropped then picked back up. Never counted. */
    SAME_HOLDER,

    /** A genuine handover to a different player. Counted. */
    TRANSFER,

    /**
     * A handover between the same two players again inside the cooldown window. Custody moves so the
     * holder stays correct, but the count is not raised, which is what stops two accounts farming it.
     */
    TRANSFER_THROTTLED,

    /** The movement had no attributable player, so custody is left untouched. */
    NO_ACTOR,

    /** The event is older than the recorded observation; custody is never rewound. */
    STALE_EVENT
}
