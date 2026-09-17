package com.itemguard.tracking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An item handed straight into a player's hand must not stay anonymous until the next sweep.
 *
 * <p>Reported by the server owner, and correct: an operator runs {@code /give}, the item lands
 * in the hotbar, and {@code /ig check} says it has no ID. Throw it on the ground and pick it
 * back up and it gets one instantly, because {@code onItemPickup} fires. Nothing tags it in
 * the hand — it waits for {@code InventoryScanTask}, which runs every 600 ticks.
 *
 * <p>That 30-second window is not just cosmetic. Two identical items issued inside it are both
 * untagged, so when the sweep reaches them each receives a <em>fresh, different</em> ID and the
 * plugin sees two legitimate items rather than a duplicate. The tool is blind to anything
 * created faster than it can mark it.
 *
 * <p>This class pins the decision rule only. Whether a given event actually reaches it is a
 * wiring question covered by the listener contract test.
 */
class InHandTaggingPolicyTest {

    private final InHandTaggingPolicy policy = new InHandTaggingPolicy();

    @Test
    @DisplayName("an untagged trackable item arriving in a slot is tagged immediately")
    void tagsOnArrival() {
        assertTrue(policy.shouldTagNow(true, false),
            "a trackable, untagged item must not wait for the periodic sweep");
    }

    @Test
    @DisplayName("an item that already has an ID is left alone")
    void doesNotRetagg() {
        assertFalse(policy.shouldTagNow(true, true),
            "re-tagging a tagged item would mint a second identity for one object");
    }

    @Test
    @DisplayName("untrackable items are ignored")
    void ignoresUntrackable() {
        // Stackable materials carry no identity in LITE; tagging them here would contradict
        // the eligibility rule the rest of the plugin enforces.
        assertFalse(policy.shouldTagNow(false, false));
        assertFalse(policy.shouldTagNow(false, true));
    }
}
