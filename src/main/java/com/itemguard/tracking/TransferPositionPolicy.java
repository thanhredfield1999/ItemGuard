package com.itemguard.tracking;

/**
 * Picks whose position a history row should carry.
 *
 * <p>Only a transfer that names a container placed in the world may record that container's block.
 * Ender chests have no single location and carried boxes travel with their owner, so both record the
 * player instead. Anything unrecognised records the player, which is always available.
 */
public final class TransferPositionPolicy {

    private TransferPositionPolicy() {
    }

    public static PositionSource sourceFor(ContainerTransfer transfer) {
        if (transfer == null) {
            return PositionSource.PLAYER;
        }
        return switch (transfer) {
            case CONTAINER_TAKE, CONTAINER_PUT, SHULKER_TAKE, SHULKER_PUT -> PositionSource.CONTAINER;
            default -> PositionSource.PLAYER;
        };
    }
}
