package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Maps a Bukkit inventory type name onto an {@link InventoryKind}.
 *
 * <p>Kept as a pure string mapping so the rules are testable without a running server. The caller
 * supplies the type name and whether the inventory has a world location; a container with no
 * location is being carried, which is the distinction that keeps shulker boxes out of the handover
 * count.
 */
class InventoryKindResolverTest {

    private static final InventoryKindResolver RESOLVER = new InventoryKindResolver();

    @Test void thePlayersOwnInventoryIsRecognised() {
        assertEquals(InventoryKind.PLAYER, RESOLVER.resolve("PLAYER", false));
        assertEquals(InventoryKind.PLAYER, RESOLVER.resolve("CRAFTING", false));
    }

    @Test void enderChestIsItsOwnKindRatherThanAGenericContainer() {
        assertEquals(InventoryKind.ENDER_CHEST, RESOLVER.resolve("ENDER_CHEST", false));
    }

    @Test void worldStorageWithALocationIsABlockContainer() {
        assertEquals(InventoryKind.BLOCK_CONTAINER, RESOLVER.resolve("CHEST", true));
        assertEquals(InventoryKind.BLOCK_CONTAINER, RESOLVER.resolve("BARREL", true));
        assertEquals(InventoryKind.BLOCK_SHULKER, RESOLVER.resolve("SHULKER_BOX", true));
        assertEquals(InventoryKind.BLOCK_CONTAINER, RESOLVER.resolve("HOPPER", true));
        assertEquals(InventoryKind.BLOCK_CONTAINER, RESOLVER.resolve("DISPENSER", true));
    }

    @Test void theSameContainerWithNoLocationIsCarriedNotPlaced() {
        // A shulker box opened from a slot has no block in the world behind it.
        assertEquals(InventoryKind.CARRIED_CONTAINER, RESOLVER.resolve("SHULKER_BOX", false));
        assertEquals(InventoryKind.CARRIED_CONTAINER, RESOLVER.resolve("CHEST", false));
    }

    @Test void aPlacedShulkerKeepsItsOwnKindInsteadOfBeingCollapsedIntoAChest() {
        assertEquals(InventoryKind.BLOCK_SHULKER, RESOLVER.resolve("SHULKER_BOX", true));
    }

    @Test void unrecognisedTypesStayUnknownSoNothingIsInvented() {
        assertEquals(InventoryKind.UNKNOWN, RESOLVER.resolve("MERCHANT", true));
        assertEquals(InventoryKind.UNKNOWN, RESOLVER.resolve("ANVIL", true));
        assertEquals(InventoryKind.UNKNOWN, RESOLVER.resolve(null, true));
        assertEquals(InventoryKind.UNKNOWN, RESOLVER.resolve("", false));
    }

    @Test void resolutionIsCaseInsensitive() {
        assertEquals(InventoryKind.ENDER_CHEST, RESOLVER.resolve("ender_chest", false));
        assertEquals(InventoryKind.BLOCK_CONTAINER, RESOLVER.resolve("chest", true));
    }
}
