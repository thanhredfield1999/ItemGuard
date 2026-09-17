package com.itemguard.tracking;

/** Which sort of inventory a clicked slot belongs to. */
public enum InventoryKind {

    /** The player's own inventory, including hotbar and armour slots. */
    PLAYER,

    /** A chest, barrel, hopper block or similar shared container placed in the world. */
    BLOCK_CONTAINER,

    /** A shulker box placed in the world: shared storage with its own timeline presentation. */
    BLOCK_SHULKER,

    /** The player's ender chest, which is private to that player. */
    ENDER_CHEST,

    /** A container opened from the player's own inventory, such as a shulker box held in a slot. */
    CARRIED_CONTAINER,

    /** Anything not recognised; treated conservatively as a plain move. */
    UNKNOWN
}
