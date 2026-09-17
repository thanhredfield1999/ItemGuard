package com.itemguard.tracking;

public final class InventoryClickPhysicalSourcePolicy {

    public InventoryClickPhysicalSource resolve(
        boolean playerInventory,
        boolean blockContainerInventory
    ) {
        if (playerInventory == blockContainerInventory) {
            return InventoryClickPhysicalSource.REJECT;
        }
        return playerInventory
            ? InventoryClickPhysicalSource.PLAYER_SLOT
            : InventoryClickPhysicalSource.BLOCK_CONTAINER_SLOT;
    }
}
