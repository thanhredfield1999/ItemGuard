package com.itemguard.tracking;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether an item is worth a tracked identity.
 *
 * <p>The plugin exists to find a lost sword or a duped enchanted tool. Logging every wooden plank
 * costs database rows and buries the interesting entries, so plain blocks and materials are skipped.
 *
 * <p>Gear is matched by suffix so every tier works without naming all of them. Anything enchanted or
 * renamed is always tracked, whatever its material: that is precisely what a duper produces.
 *
 * <p>Unrecognised materials are not tracked. A future block should not silently start filling the
 * database.
 */
public final class TrackingWorthinessPolicy {

    /** Gear suffixes: swords, tools, armour pieces across every tier. */
    private static final Set<String> GEAR_SUFFIXES = Set.of(
        "_SWORD", "_PICKAXE", "_AXE", "_SHOVEL", "_HOE",
        "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS"
    );

    /** Individually valuable items with no shared suffix. */
    private static final Set<String> VALUABLES = Set.of(
        "BOW", "CROSSBOW", "TRIDENT", "SHIELD",
        "WOODEN_SPEAR", "STONE_SPEAR", "COPPER_SPEAR", "IRON_SPEAR", "GOLDEN_SPEAR",
        "DIAMOND_SPEAR", "NETHERITE_SPEAR",
        "ELYTRA", "ENCHANTED_BOOK",
        "TURTLE_HELMET", "CARROT_ON_A_STICK", "WARPED_FUNGUS_ON_A_STICK",
        "MACE"
    );

    /** LITE deliberately keeps its evidence set narrow; Full may opt in via force-track-materials. */
    private static final Set<String> LITE_EXCLUDED = Set.of(
        "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "TOTEM_OF_UNDYING",
        "NETHERITE_INGOT", "NETHERITE_SCRAP", "NETHERITE_UPGRADE_SMITHING_TEMPLATE",
        "DRAGON_EGG", "NETHER_STAR", "BEACON", "CONDUIT", "HEART_OF_THE_SEA",
        "FISHING_ROD", "SHEARS", "FLINT_AND_STEEL", "BRUSH", "SPYGLASS", "RECOVERY_COMPASS"
    );

    public static boolean isLiteExcluded(String materialName) {
        return materialName != null && LITE_EXCLUDED.contains(materialName.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * @param materialName the Bukkit material name
     * @param enchanted    whether the item carries any enchantment
     * @param customNamed  whether the item has a custom display name
     */
    public boolean isWorthTracking(String materialName, boolean enchanted, boolean customNamed) {
        if (enchanted || customNamed) {
            // Enchanted or named items are the whole reason this plugin exists.
            return true;
        }
        if (materialName == null || materialName.isBlank()) {
            return false;
        }
        String material = materialName.trim().toUpperCase(Locale.ROOT);
        if (VALUABLES.contains(material)) {
            return true;
        }
        for (String suffix : GEAR_SUFFIXES) {
            if (material.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
