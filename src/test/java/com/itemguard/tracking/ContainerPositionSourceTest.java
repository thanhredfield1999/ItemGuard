package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Picks which of the two inventories in a click is the container whose position should be recorded.
 *
 * <p>Found by testing: the first version asked the <em>clicked</em> inventory for its location. When
 * a player shift-clicks out of their own inventory, the clicked inventory is the player's, and
 * Bukkit answers that with the player's own position — so every chest row recorded where the player
 * stood, and two jumps to one chest landed in different places.
 */
class ContainerPositionSourceTest {

    @Test void theBlockContainerIsChosenWhicheverSideWasClicked() {
        assertEquals(ClickSide.TOP,
            ContainerPositionSource.pick(InventoryKind.PLAYER, InventoryKind.BLOCK_CONTAINER));
        assertEquals(ClickSide.CLICKED,
            ContainerPositionSource.pick(InventoryKind.BLOCK_CONTAINER, InventoryKind.PLAYER));
    }

    @Test void aPlacedShulkerIsAlsoChosenAsAWorldContainer() {
        assertEquals(ClickSide.TOP,
            ContainerPositionSource.pick(InventoryKind.PLAYER, InventoryKind.BLOCK_SHULKER));
        assertEquals(ClickSide.CLICKED,
            ContainerPositionSource.pick(InventoryKind.BLOCK_SHULKER, InventoryKind.PLAYER));
    }

    @Test void aPlayerInventoryIsNeverTheContainerPosition() {
        // This is the exact defect: the player's inventory reports the player's position.
        assertEquals(ClickSide.NONE,
            ContainerPositionSource.pick(InventoryKind.PLAYER, InventoryKind.PLAYER));
    }

    @Test void anEnderChestHasNoBlockPosition() {
        assertEquals(ClickSide.NONE,
            ContainerPositionSource.pick(InventoryKind.PLAYER, InventoryKind.ENDER_CHEST));
        assertEquals(ClickSide.NONE,
            ContainerPositionSource.pick(InventoryKind.ENDER_CHEST, InventoryKind.PLAYER));
    }

    @Test void aCarriedBoxHasNoBlockPosition() {
        assertEquals(ClickSide.NONE,
            ContainerPositionSource.pick(InventoryKind.PLAYER, InventoryKind.CARRIED_CONTAINER));
        assertEquals(ClickSide.NONE,
            ContainerPositionSource.pick(InventoryKind.CARRIED_CONTAINER, InventoryKind.PLAYER));
    }

    @Test void chestToChestPrefersTheClickedSide() {
        assertEquals(ClickSide.CLICKED,
            ContainerPositionSource.pick(InventoryKind.BLOCK_CONTAINER, InventoryKind.BLOCK_CONTAINER));
    }

    @Test void unknownInventoriesYieldNoPosition() {
        assertEquals(ClickSide.NONE,
            ContainerPositionSource.pick(InventoryKind.UNKNOWN, InventoryKind.UNKNOWN));
        assertEquals(ClickSide.NONE, ContainerPositionSource.pick(null, null));
    }
}
