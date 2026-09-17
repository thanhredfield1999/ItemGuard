package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which position a history row should carry.
 *
 * <p>Every action currently records the player's position. For a chest transfer that is where the
 * player stood, not where the chest is — and a player can reach several containers from one spot and
 * walk away immediately. Recovering a lost item needs the container's position.
 */
class TransferPositionPolicyTest {

    @Test void containerTransfersRecordTheContainer() {
        assertEquals(PositionSource.CONTAINER,
            TransferPositionPolicy.sourceFor(ContainerTransfer.CONTAINER_TAKE));
        assertEquals(PositionSource.CONTAINER,
            TransferPositionPolicy.sourceFor(ContainerTransfer.CONTAINER_PUT));
    }

    @Test void placedShulkerTransfersRecordTheShulkerBlock() {
        assertEquals(PositionSource.CONTAINER,
            TransferPositionPolicy.sourceFor(ContainerTransfer.SHULKER_TAKE));
        assertEquals(PositionSource.CONTAINER,
            TransferPositionPolicy.sourceFor(ContainerTransfer.SHULKER_PUT));
    }

    @Test void enderChestRecordsThePlayerBecauseItHasNoSingleLocation() {
        assertEquals(PositionSource.PLAYER,
            TransferPositionPolicy.sourceFor(ContainerTransfer.ENDERCHEST_TAKE));
        assertEquals(PositionSource.PLAYER,
            TransferPositionPolicy.sourceFor(ContainerTransfer.ENDERCHEST_PUT));
    }

    @Test void carriedContainersRecordThePlayerBecauseTheyTravelWithThem() {
        assertEquals(PositionSource.PLAYER,
            TransferPositionPolicy.sourceFor(ContainerTransfer.CARRIED_CONTAINER_TAKE));
        assertEquals(PositionSource.PLAYER,
            TransferPositionPolicy.sourceFor(ContainerTransfer.CARRIED_CONTAINER_PUT));
    }

    @Test void plainInventoryMovesRecordThePlayer() {
        assertEquals(PositionSource.PLAYER,
            TransferPositionPolicy.sourceFor(ContainerTransfer.INVENTORY_MOVE));
    }

    @Test void anUnknownTransferFallsBackToThePlayerRatherThanNothing() {
        assertEquals(PositionSource.PLAYER, TransferPositionPolicy.sourceFor(null));
    }

    @Test void everyTransferHasADeclaredPositionSource() {
        for (ContainerTransfer transfer : ContainerTransfer.values()) {
            assertNotNull(TransferPositionPolicy.sourceFor(transfer), transfer.name());
        }
    }

    @Test void onlyTransfersNamingAWorldContainerUseTheContainerPosition() {
        // Keeps the rule honest: if it does not touch a placed container, it cannot claim one.
        for (ContainerTransfer transfer : ContainerTransfer.values()) {
            boolean usesContainer =
                TransferPositionPolicy.sourceFor(transfer) == PositionSource.CONTAINER;
            boolean namesWorldContainer = transfer == ContainerTransfer.CONTAINER_TAKE
                || transfer == ContainerTransfer.CONTAINER_PUT
                || transfer == ContainerTransfer.SHULKER_TAKE
                || transfer == ContainerTransfer.SHULKER_PUT;
            assertEquals(namesWorldContainer, usesContainer, transfer.name());
        }
    }
}
