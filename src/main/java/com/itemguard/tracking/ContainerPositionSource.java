package com.itemguard.tracking;

/**
 * Picks which side of a click holds the container whose position belongs in the history row.
 *
 * <p>Only a container placed in the world has a block position. Asking a player's inventory for its
 * location returns the <em>player's</em> position, which is how chest rows ended up recording where
 * somebody stood; ender chests and carried boxes have no block at all.
 */
public final class ContainerPositionSource {

    private ContainerPositionSource() {
    }

    public static ClickSide pick(InventoryKind clicked, InventoryKind top) {
        if (isWorldContainer(clicked)) {
            return ClickSide.CLICKED;
        }
        if (isWorldContainer(top)) {
            return ClickSide.TOP;
        }
        return ClickSide.NONE;
    }

    private static boolean isWorldContainer(InventoryKind kind) {
        return kind == InventoryKind.BLOCK_CONTAINER || kind == InventoryKind.BLOCK_SHULKER;
    }
}
