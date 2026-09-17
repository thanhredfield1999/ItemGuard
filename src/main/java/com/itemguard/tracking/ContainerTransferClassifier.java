package com.itemguard.tracking;

/**
 * Turns the pair of inventories an item moved between into a named transfer.
 *
 * <p>Direction is derived from where the item ended up, never guessed: recording a put as a take
 * would move custody to the player who gave the item away, producing confident wrong attribution.
 * Anything unrecognised degrades to {@link ContainerTransfer#INVENTORY_MOVE}, which claims nothing.
 */
public final class ContainerTransferClassifier {

    public ContainerTransfer classify(InventoryKind from, InventoryKind to) {
        InventoryKind source = from == null ? InventoryKind.UNKNOWN : from;
        InventoryKind destination = to == null ? InventoryKind.UNKNOWN : to;

        if (source == InventoryKind.UNKNOWN || destination == InventoryKind.UNKNOWN) {
            return ContainerTransfer.INVENTORY_MOVE;
        }
        if (source == destination) {
            // Within one inventory nothing changed hands. Between two world containers the goods
            // moved but the clicker did not acquire them, so it is recorded as storage, not a take.
            return switch (source) {
                case BLOCK_CONTAINER -> ContainerTransfer.CONTAINER_PUT;
                case BLOCK_SHULKER -> ContainerTransfer.SHULKER_PUT;
                default -> ContainerTransfer.INVENTORY_MOVE;
            };
        }
        if (destination == InventoryKind.PLAYER) {
            return switch (source) {
                case BLOCK_CONTAINER -> ContainerTransfer.CONTAINER_TAKE;
                case BLOCK_SHULKER -> ContainerTransfer.SHULKER_TAKE;
                case ENDER_CHEST -> ContainerTransfer.ENDERCHEST_TAKE;
                case CARRIED_CONTAINER -> ContainerTransfer.CARRIED_CONTAINER_TAKE;
                default -> ContainerTransfer.INVENTORY_MOVE;
            };
        }
        if (source == InventoryKind.PLAYER) {
            return switch (destination) {
                case BLOCK_CONTAINER -> ContainerTransfer.CONTAINER_PUT;
                case BLOCK_SHULKER -> ContainerTransfer.SHULKER_PUT;
                case ENDER_CHEST -> ContainerTransfer.ENDERCHEST_PUT;
                case CARRIED_CONTAINER -> ContainerTransfer.CARRIED_CONTAINER_PUT;
                default -> ContainerTransfer.INVENTORY_MOVE;
            };
        }
        // Container to a different container: goods moved, custody did not.
        return source == InventoryKind.BLOCK_SHULKER || destination == InventoryKind.BLOCK_SHULKER
            ? ContainerTransfer.SHULKER_PUT
            : ContainerTransfer.CONTAINER_PUT;
    }
}
