package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryClickPhysicalSourcePolicyTest {

    private final InventoryClickPhysicalSourcePolicy policy =
        new InventoryClickPhysicalSourcePolicy();

    @Test
    void selectsPlayerSlotForPlayersOwnInventory() {
        assertEquals(
            InventoryClickPhysicalSource.PLAYER_SLOT,
            policy.resolve(true, false)
        );
    }

    @Test
    void selectsBlockContainerOnlyForPhysicalBlockInventory() {
        assertEquals(
            InventoryClickPhysicalSource.BLOCK_CONTAINER_SLOT,
            policy.resolve(false, true)
        );
    }

    @Test
    void rejectsVirtualOrAmbiguousInventory() {
        assertEquals(
            InventoryClickPhysicalSource.REJECT,
            policy.resolve(false, false)
        );
        assertEquals(
            InventoryClickPhysicalSource.REJECT,
            policy.resolve(true, true)
        );
    }
}
