package com.itemguard.tracking;

/** Whose position a history row records. */
public enum PositionSource {

    /** Where the acting player was standing. */
    PLAYER,

    /** Where the container block is. Needed to find a lost item again. */
    CONTAINER
}
