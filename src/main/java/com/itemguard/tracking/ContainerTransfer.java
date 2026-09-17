package com.itemguard.tracking;

/**
 * A named item movement, replacing the single {@code INVENTORY_MOVE} label that made every chest,
 * hopper and ender chest transfer look identical.
 *
 * <p>Each constant states explicitly whether it hands the item to a new holder. Only taking an item
 * out of shared world storage does: storing is a release, and both ender chests and carried shulkers
 * are private to the player who opened them, so counting those would let one player inflate their own
 * chain of custody with no second party involved.
 */
public enum ContainerTransfer {

    /** Taken out of a chest, barrel or similar placed in the world. A genuine change of hands. */
    CONTAINER_TAKE("CONTAINER_TAKE", true),

    /** Put into world storage. The item left the player's hands but nobody has claimed it yet. */
    CONTAINER_PUT("CONTAINER_PUT", false),

    /** Taken out of a shulker box placed in the world. A genuine change of hands. */
    SHULKER_TAKE("SHULKER_TAKE", true),

    /** Put into a shulker box placed in the world. The item left the player's hands. */
    SHULKER_PUT("SHULKER_PUT", false),

    /** Taken out of the player's own ender chest. Private storage, so the holder is unchanged. */
    ENDERCHEST_TAKE("ENDERCHEST_TAKE", false),

    /** Put into the player's own ender chest. */
    ENDERCHEST_PUT("ENDERCHEST_PUT", false),

    /** Taken out of a container carried in the player's own inventory, such as a shulker box. */
    CARRIED_CONTAINER_TAKE("CARRIED_CONTAINER_TAKE", false),

    /** Put into a container carried in the player's own inventory. */
    CARRIED_CONTAINER_PUT("CARRIED_CONTAINER_PUT", false),

    /** Moved between slots of the same player's inventory. */
    INVENTORY_MOVE("INVENTORY_MOVE", false);

    private final String action;
    private final boolean claimsCustody;

    ContainerTransfer(String action, boolean claimsCustody) {
        this.action = action;
        this.claimsCustody = claimsCustody;
    }

    /** The action string written to history. */
    public String action() {
        return action;
    }

    /** Whether this movement means a new player now holds the item. */
    public boolean claimsCustody() {
        return claimsCustody;
    }
}
