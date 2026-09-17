package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which items are worth tracking at all.
 *
 * <p>The point of the plugin is finding a lost sword or a duped enchanted tool, not logging every
 * wooden plank a player carries. Tracking everything with a stack size of one still pulls in a lot
 * of junk, and every tracked item costs database rows.
 */
class TrackingWorthinessPolicyTest {

    private static final TrackingWorthinessPolicy POLICY = new TrackingWorthinessPolicy();

    @Test void weaponsAndArmourAreWorthTracking() {
        assertTrue(POLICY.isWorthTracking("DIAMOND_SWORD", false, false));
        assertTrue(POLICY.isWorthTracking("NETHERITE_CHESTPLATE", false, false));
        assertTrue(POLICY.isWorthTracking("BOW", false, false));
        assertTrue(POLICY.isWorthTracking("TRIDENT", false, false));
        assertTrue(POLICY.isWorthTracking("DIAMOND_SPEAR", false, false));
        assertTrue(POLICY.isWorthTracking("NETHERITE_SPEAR", false, false));
        assertTrue(POLICY.isWorthTracking("SHIELD", false, false));
    }

    @Test void realToolsAreWorthTracking() {
        assertTrue(POLICY.isWorthTracking("DIAMOND_PICKAXE", false, false));
        assertTrue(POLICY.isWorthTracking("NETHERITE_AXE", false, false));
        assertTrue(POLICY.isWorthTracking("IRON_SHOVEL", false, false));
        assertFalse(POLICY.isWorthTracking("FISHING_ROD", false, false));
        assertFalse(POLICY.isWorthTracking("SHEARS", false, false));
        assertFalse(POLICY.isWorthTracking("FLINT_AND_STEEL", false, false));
    }

    @Test void plainBlocksAndMaterialsAreNotTracked() {
        assertFalse(POLICY.isWorthTracking("OAK_PLANKS", false, false));
        assertFalse(POLICY.isWorthTracking("OAK_LOG", false, false));
        assertFalse(POLICY.isWorthTracking("COBBLESTONE", false, false));
        assertFalse(POLICY.isWorthTracking("DIRT", false, false));
        assertFalse(POLICY.isWorthTracking("BREAD", false, false));
    }

    @Test void anythingEnchantedIsTrackedWhateverItIs() {
        // An enchanted book or an enchanted plank-like oddity is exactly what a duper produces.
        assertTrue(POLICY.isWorthTracking("ENCHANTED_BOOK", true, false));
        assertTrue(POLICY.isWorthTracking("OAK_PLANKS", true, false),
            "an enchantment makes any item interesting");
    }

    @Test void anythingWithACustomNameIsTracked() {
        assertTrue(POLICY.isWorthTracking("STICK", false, true),
            "a named stick is somebody's prized item, not junk");
    }

    @Test void valuableGearIsTrackedEvenWithoutEnchantments() {
        assertTrue(POLICY.isWorthTracking("ELYTRA", false, false));
        assertFalse(POLICY.isWorthTracking("TOTEM_OF_UNDYING", false, false));
        assertFalse(POLICY.isWorthTracking("ENCHANTED_GOLDEN_APPLE", false, false));
        assertFalse(POLICY.isWorthTracking("NETHERITE_INGOT", false, false));
        assertFalse(POLICY.isWorthTracking("DRAGON_EGG", false, false));
        assertFalse(POLICY.isWorthTracking("BRUSH", false, false));
        assertFalse(POLICY.isWorthTracking("SPYGLASS", false, false));
        assertFalse(POLICY.isWorthTracking("RECOVERY_COMPASS", false, false));
    }

    @Test void unknownMaterialsAreNotTrackedByDefault() {
        // Fail-quiet: a material this build does not recognise is not silently tracked.
        assertFalse(POLICY.isWorthTracking("SOME_FUTURE_BLOCK", false, false));
        assertFalse(POLICY.isWorthTracking(null, false, false));
        assertFalse(POLICY.isWorthTracking("", false, false));
    }

    @Test void wornGearCategoriesAreMatchedBySuffixNotAHardcodedList() {
        // Every tier must work without listing all of them.
        for (String tier : new String[]{"WOODEN", "STONE", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"}) {
            assertTrue(POLICY.isWorthTracking(tier + "_SWORD", false, false), tier);
            assertTrue(POLICY.isWorthTracking(tier + "_PICKAXE", false, false), tier);
        }
        for (String piece : new String[]{"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"}) {
            assertTrue(POLICY.isWorthTracking("IRON_" + piece, false, false), piece);
        }
    }

    @Test void caseAndWhitespaceDoNotMatter() {
        assertTrue(POLICY.isWorthTracking("  diamond_sword  ", false, false));
        assertFalse(POLICY.isWorthTracking("  oak_planks  ", false, false));
    }

    @Test void liteExcludesRemainExcludedEvenWhenNamedOrEnchanted() {
        assertTrue(TrackingWorthinessPolicy.isLiteExcluded("TOTEM_OF_UNDYING"));
        assertTrue(TrackingWorthinessPolicy.isLiteExcluded("GOLDEN_APPLE"));
        assertTrue(TrackingWorthinessPolicy.isLiteExcluded("NETHERITE_INGOT"));
        assertTrue(TrackingWorthinessPolicy.isLiteExcluded("FISHING_ROD"));
        assertTrue(TrackingWorthinessPolicy.isLiteExcluded("RECOVERY_COMPASS"));
        assertFalse(TrackingWorthinessPolicy.isLiteExcluded("ELYTRA"));
        assertFalse(TrackingWorthinessPolicy.isLiteExcluded("DIAMOND_SPEAR"));
    }
}
