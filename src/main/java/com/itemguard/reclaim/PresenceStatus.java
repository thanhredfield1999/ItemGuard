package com.itemguard.reclaim;

public enum PresenceStatus {
    ABSENT,
    PRESENT,
    UNAVAILABLE,
    ERROR,

    /**
     * The source does not exist on this server, so it was not queried.
     *
     * <p>Deliberately distinct from {@link #UNAVAILABLE}, which means the source exists and could not
     * be read. An absent plugin holds no items, so there is nothing to prove; an unreadable one might
     * hold the identity, which is why it denies. The distinction is the whole reason a reclaim can
     * ever succeed on a server that does not run PlayerVaults or zAuctionHouse, and it is only
     * reached when the owner sets {@code reclaim.external-absence-mode: INSTALLED_ONLY}. This status
     * is never blocking, and it is recorded in the decision evidence so an operator can see which
     * sources were skipped rather than checked.
     */
    NOT_APPLICABLE
}
