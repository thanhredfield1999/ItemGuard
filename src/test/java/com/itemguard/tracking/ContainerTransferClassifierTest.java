package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Classifies an inventory click into a named transfer, so history can say which container an item
 * came out of instead of labelling everything {@code INVENTORY_MOVE}.
 *
 * <p>Threat model: {@code docs/design/2026-09-12-container-transfer-classification.md}. The naive
 * version of this — treat every container take as a handover — lets a player inflate their own
 * custody chain through an ender chest, so private storage is classified separately from shared
 * storage, and direction is derived rather than guessed.
 */
class ContainerTransferClassifierTest {

    private static final ContainerTransferClassifier CLASSIFIER = new ContainerTransferClassifier();

    @Test void takingFromAChestIsAContainerTake() {
        assertEquals(ContainerTransfer.CONTAINER_TAKE,
            CLASSIFIER.classify(InventoryKind.BLOCK_CONTAINER, InventoryKind.PLAYER));
    }

    @Test void puttingIntoAChestIsAContainerPut() {
        assertEquals(ContainerTransfer.CONTAINER_PUT,
            CLASSIFIER.classify(InventoryKind.PLAYER, InventoryKind.BLOCK_CONTAINER));
    }

    @Test void aPlacedShulkerIsNamedAsAShulkerRatherThanAChest() {
        assertEquals(ContainerTransfer.SHULKER_TAKE,
            CLASSIFIER.classify(InventoryKind.BLOCK_SHULKER, InventoryKind.PLAYER));
        assertEquals(ContainerTransfer.SHULKER_PUT,
            CLASSIFIER.classify(InventoryKind.PLAYER, InventoryKind.BLOCK_SHULKER));
    }

    @Test void enderChestIsNamedSeparatelyBecauseItIsPrivateStorage() {
        assertEquals(ContainerTransfer.ENDERCHEST_TAKE,
            CLASSIFIER.classify(InventoryKind.ENDER_CHEST, InventoryKind.PLAYER));
        assertEquals(ContainerTransfer.ENDERCHEST_PUT,
            CLASSIFIER.classify(InventoryKind.PLAYER, InventoryKind.ENDER_CHEST));
    }

    @Test void movingWithinYourOwnInventoryStaysAPlainMove() {
        assertEquals(ContainerTransfer.INVENTORY_MOVE,
            CLASSIFIER.classify(InventoryKind.PLAYER, InventoryKind.PLAYER));
    }

    @Test void carriedStorageOpenedFromYourOwnInventoryIsNotAHandover() {
        // A shulker opened out of the player's own inventory never leaves that player.
        assertEquals(ContainerTransfer.CARRIED_CONTAINER_TAKE,
            CLASSIFIER.classify(InventoryKind.CARRIED_CONTAINER, InventoryKind.PLAYER));
        assertEquals(ContainerTransfer.CARRIED_CONTAINER_PUT,
            CLASSIFIER.classify(InventoryKind.PLAYER, InventoryKind.CARRIED_CONTAINER));
    }

    @Test void unknownInventoriesFallBackToThePlainMoveRatherThanInventingATransfer() {
        assertEquals(ContainerTransfer.INVENTORY_MOVE,
            CLASSIFIER.classify(InventoryKind.UNKNOWN, InventoryKind.PLAYER));
        assertEquals(ContainerTransfer.INVENTORY_MOVE,
            CLASSIFIER.classify(InventoryKind.PLAYER, InventoryKind.UNKNOWN));
        assertEquals(ContainerTransfer.INVENTORY_MOVE, CLASSIFIER.classify(null, null));
    }

    @Test void containerToContainerIsNotAttributedAsATakeByThatPlayer() {
        // Shift-clicking between two chests is movement of goods, not acquisition by the clicker.
        assertEquals(ContainerTransfer.CONTAINER_PUT,
            CLASSIFIER.classify(InventoryKind.BLOCK_CONTAINER, InventoryKind.BLOCK_CONTAINER));
    }

    @Test void everyTransferDeclaresWhetherItClaimsCustodyExplicitly() {
        // Only a take out of shared world storage puts the item in a new pair of hands.
        assertTrue(ContainerTransfer.CONTAINER_TAKE.claimsCustody());
        assertTrue(ContainerTransfer.SHULKER_TAKE.claimsCustody());

        assertFalse(ContainerTransfer.CONTAINER_PUT.claimsCustody(), "storing is releasing");
        assertFalse(ContainerTransfer.SHULKER_PUT.claimsCustody(), "storing is releasing");
        assertFalse(ContainerTransfer.ENDERCHEST_TAKE.claimsCustody(),
            "ender chest is private, so the holder never changed");
        assertFalse(ContainerTransfer.ENDERCHEST_PUT.claimsCustody());
        assertFalse(ContainerTransfer.CARRIED_CONTAINER_TAKE.claimsCustody(),
            "a shulker in your own bag is still your own hands");
        assertFalse(ContainerTransfer.CARRIED_CONTAINER_PUT.claimsCustody());
        assertFalse(ContainerTransfer.INVENTORY_MOVE.claimsCustody());
    }

    @Test void theActionNameIsWhatGetsWrittenToHistory() {
        assertEquals("CONTAINER_TAKE", ContainerTransfer.CONTAINER_TAKE.action());
        assertEquals("CONTAINER_PUT", ContainerTransfer.CONTAINER_PUT.action());
        assertEquals("SHULKER_TAKE", ContainerTransfer.SHULKER_TAKE.action());
        assertEquals("SHULKER_PUT", ContainerTransfer.SHULKER_PUT.action());
        assertEquals("ENDERCHEST_TAKE", ContainerTransfer.ENDERCHEST_TAKE.action());
        assertEquals("ENDERCHEST_PUT", ContainerTransfer.ENDERCHEST_PUT.action());
        assertEquals("INVENTORY_MOVE", ContainerTransfer.INVENTORY_MOVE.action());
    }
}
