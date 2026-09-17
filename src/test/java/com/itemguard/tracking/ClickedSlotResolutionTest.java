package com.itemguard.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which slot index may be read back from the player's inventory after a click?
 *
 * <p>{@code InventoryClickEvent.getSlot()} is an index <em>into the inventory that was
 * clicked</em>, not into the player's inventory. Clicking slot 5 of an open chest and then
 * calling {@code player.getInventory().getItem(5)} reads the player's sixth hotbar slot — a
 * different item entirely, or nothing at all.
 *
 * <p>The consequences are both wrong and silent:
 * <ul>
 *   <li>the item the player actually clicked never gets tagged, and
 *   <li>an unrelated item that happens to sit at the same index might be.
 * </ul>
 *
 * <p>Neither produces an error. The first LITE build with in-hand tagging shipped this, because
 * the only fixture case clicked in the hotbar with no container open, where the two indices
 * happen to agree.
 *
 * <p>So the read-back is only valid when the clicked inventory <em>is</em> the player's own.
 * For every other case the correct action is to do nothing and let the periodic sweep handle
 * it — a late tag is a small problem, tagging the wrong item is a real one.
 */
class ClickedSlotResolutionTest {

    private final ClickedSlotResolution resolution = new ClickedSlotResolution();

    @Test
    @DisplayName("Clicking in the player's own inventory: the slot index is usable")
    void ownInventoryClickResolves() {
        assertTrue(resolution.canReadBackFromPlayerInventory(true, 7));
        assertEquals(7, resolution.playerSlotOf(true, 7));
    }

    @Test
    @DisplayName("Clicking inside a chest: the index belongs to the chest, not the player")
    void containerClickDoesNotResolve() {
        // Chest slot 5 is not player slot 5. Reading it back would inspect the wrong item.
        assertFalse(resolution.canReadBackFromPlayerInventory(false, 5));
        assertEquals(-1, resolution.playerSlotOf(false, 5));
    }

    @Test
    @DisplayName("A click outside any inventory resolves to nothing")
    void clickOutsideResolvesToNothing() {
        // Bukkit reports slot -999 for a click in the empty space outside the window.
        assertFalse(resolution.canReadBackFromPlayerInventory(true, -999));
        assertFalse(resolution.canReadBackFromPlayerInventory(false, -999));
    }

    @Test
    @DisplayName("A negative slot is never read back, whoever owns the inventory")
    void negativeSlotsNeverResolve() {
        assertFalse(resolution.canReadBackFromPlayerInventory(true, -1));
    }

    @Test
    @DisplayName("Armour and offhand slots in the player's inventory are still the player's")
    void armourAndOffhandResolve() {
        // PlayerInventory indices: 36-39 armour, 40 offhand. They are ordinary player slots and
        // must not be excluded - an operator handing over armour should have it tagged too.
        for (int slot : new int[] {36, 37, 38, 39, 40}) {
            assertTrue(resolution.canReadBackFromPlayerInventory(true, slot),
                "slot " + slot + " is part of the player's inventory");
            assertEquals(slot, resolution.playerSlotOf(true, slot));
        }
    }

    @Test
    @DisplayName("A slot past the end of the player's inventory is refused")
    void slotBeyondPlayerInventoryIsRefused() {
        // A double chest reports indices well past 40. Even flagged as the player's inventory,
        // an out-of-range index must not be read back.
        assertFalse(resolution.canReadBackFromPlayerInventory(true, 53));
    }
}
