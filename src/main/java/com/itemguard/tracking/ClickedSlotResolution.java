package com.itemguard.tracking;

/**
 * Decides whether a clicked slot index can be read back from the player's own inventory.
 *
 * <p>{@code InventoryClickEvent.getSlot()} indexes the inventory that was clicked. When a chest
 * is open, slot 5 is the chest's sixth slot and has nothing to do with the player's slot 5.
 * Reading it back from the player would inspect an unrelated item — tagging the wrong object
 * while leaving the clicked one untagged, with no error either way.
 *
 * <p>When the index cannot be trusted, the right answer is to do nothing: the periodic sweep
 * will tag the item within its interval. A late tag is a small problem; tagging the wrong item
 * corrupts the record.
 */
public final class ClickedSlotResolution {

    /** Highest index in a PlayerInventory: 0-35 storage, 36-39 armour, 40 offhand. */
    private static final int MAX_PLAYER_SLOT = 40;

    /**
     * @param clickedPlayerInventory whether the clicked inventory is the player's own
     * @param slot                   the raw slot index from the click event
     */
    public boolean canReadBackFromPlayerInventory(boolean clickedPlayerInventory, int slot) {
        // Bukkit reports -999 for a click outside any window, and other negatives for
        // non-slot regions.
        return clickedPlayerInventory && slot >= 0 && slot <= MAX_PLAYER_SLOT;
    }

    /**
     * @return the player-inventory slot to read, or -1 when the index cannot be trusted
     */
    public int playerSlotOf(boolean clickedPlayerInventory, int slot) {
        return canReadBackFromPlayerInventory(clickedPlayerInventory, slot) ? slot : -1;
    }
}
