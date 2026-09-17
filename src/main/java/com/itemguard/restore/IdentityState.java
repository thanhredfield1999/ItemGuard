package com.itemguard.restore;

/** What the records say about a tracked identity right now. */
public enum IdentityState {

    /** A player or container currently holds it. */
    HELD,

    /** On the ground as a dropped item. */
    DROPPED,

    /**
     * Confirmed gone: absent from a completed observation sweep after a releasing action. A recorded
     * state, never a deletion, so a later restore stays auditable.
     */
    DESTROYED,

    /** Not in the records, or a state this build does not recognise. Always refuses. */
    UNKNOWN
}
