package com.itemguard.listeners;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The in-hand tagging decision has to be reachable from a real event, not merely correct.
 *
 * <p>A policy class with no call site is the failure mode already hit twice in this project:
 * {@code RestoreGate} existed and was never invoked, and {@code markProbeInitiatedClose} had a
 * setter nobody called. Both read as protection and enforced nothing. So this test asserts the
 * wiring, by source, rather than trusting that it was hooked up.
 *
 * <p>Two events matter for "an item appears directly in a player's inventory":
 * <ul>
 *   <li>{@code PlayerItemHeldEvent} — the operator runs /give and the item lands in the hotbar;
 *       switching to or holding that slot is the first observable moment.</li>
 *   <li>{@code InventoryClickEvent} — the item is moved by hand within the inventory.</li>
 * </ul>
 */
class InHandTaggingWiringTest {

    @Test
    @DisplayName("ItemListener consults the in-hand policy on inventory click")
    void clickPathIsWired() throws IOException {
        String source = read("src/main/java/com/itemguard/listeners/ItemListener.java");
        int click = source.indexOf("onInventoryClick");
        assertTrue(click > 0, "onInventoryClick not found");

        String handler = source.substring(click, Math.min(source.length(), click + 3000));
        assertTrue(
            handler.contains("tagUntrackedInHand") || handler.contains("inHandTagging"),
            "onInventoryClick returns early for untagged items and never tags them; an item "
                + "moved by hand stays anonymous until the 600-tick sweep"
        );
    }

    @Test
    @DisplayName("a held-item event can tag a freshly issued item")
    void heldPathIsWired() throws IOException {
        String source = read("src/main/java/com/itemguard/listeners/ItemListener.java");
        assertTrue(
            source.contains("PlayerItemHeldEvent"),
            "nothing reacts to a player holding a slot, so /give followed by holding the item "
                + "leaves it untagged until the periodic sweep"
        );
    }

    private String read(String relative) throws IOException {
        return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("The click path routes its slot through ClickedSlotResolution")
    void clickPathValidatesTheSlotBeforeReadingItBack() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/com/itemguard/listeners/ItemListener.java"),
            StandardCharsets.UTF_8);

        // A raw event.getSlot() handed to the tagging path would read the clicked container's
        // index out of the player's inventory - the wrong item, with no error. RestoreGate and
        // markProbeInitiatedClose both existed and were never called; a policy class nothing
        // invokes is worse than no policy, because it reads as covered.
        assertTrue(source.contains("clickedSlots.playerSlotOf("),
            "ItemListener must resolve the clicked slot before reading it back");
        assertTrue(source.contains("event.getClickedInventory() == player.getInventory()"),
            "resolution must be told whether the clicked inventory is the player's own");
    }
}
