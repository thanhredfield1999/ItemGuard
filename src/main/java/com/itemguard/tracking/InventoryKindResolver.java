package com.itemguard.tracking;

import java.util.Locale;
import java.util.Set;

/**
 * Classifies an inventory by its Bukkit type name and whether it has a world location.
 *
 * <p>Pure string mapping, so the rules can be tested without a server. The location flag carries the
 * distinction that matters: the same {@code SHULKER_BOX} type is shared world storage when a block
 * backs it and private carried storage when it was opened from a player's own slot.
 *
 * <p>Storage types are an allowlist. An unrecognised type resolves to {@link InventoryKind#UNKNOWN},
 * which downstream degrades to a plain move rather than inventing a transfer.
 */
public final class InventoryKindResolver {

    private static final Set<String> PLAYER_TYPES = Set.of("PLAYER", "CRAFTING");

    private static final Set<String> STORAGE_TYPES = Set.of(
        "CHEST",
        "BARREL",
        "SHULKER_BOX",
        "HOPPER",
        "DISPENSER",
        "DROPPER"
    );

    /**
     * @param inventoryTypeName Bukkit {@code InventoryType} name, or null
     * @param hasWorldLocation  whether the inventory is backed by a block in the world
     */
    public InventoryKind resolve(String inventoryTypeName, boolean hasWorldLocation) {
        if (inventoryTypeName == null || inventoryTypeName.isBlank()) {
            return InventoryKind.UNKNOWN;
        }
        String type = inventoryTypeName.trim().toUpperCase(Locale.ROOT);
        if (PLAYER_TYPES.contains(type)) {
            return InventoryKind.PLAYER;
        }
        if ("ENDER_CHEST".equals(type)) {
            return InventoryKind.ENDER_CHEST;
        }
        if (STORAGE_TYPES.contains(type)) {
            if (!hasWorldLocation) {
                return InventoryKind.CARRIED_CONTAINER;
            }
            return "SHULKER_BOX".equals(type)
                ? InventoryKind.BLOCK_SHULKER
                : InventoryKind.BLOCK_CONTAINER;
        }
        return InventoryKind.UNKNOWN;
    }
}
