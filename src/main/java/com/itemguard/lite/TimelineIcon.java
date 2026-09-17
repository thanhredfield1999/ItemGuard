package com.itemguard.lite;

import org.bukkit.Material;

/**
 * Which icon a timeline row renders.
 *
 * <p>A row's subject is where the item went, so a chest row shows a chest and an ender chest row
 * shows an ender chest. Rows about a player carrying, dropping or losing the item show that player.
 */
public enum TimelineIcon {

    /** Stored in or taken from a chest, barrel or similar placed in the world. */
    CHEST(Material.CHEST),

    /** Stored in or taken from the player's own ender chest. */
    ENDER_CHEST(Material.ENDER_CHEST),

    /** Stored in or taken from a container carried in the player's inventory. */
    SHULKER_BOX(Material.SHULKER_BOX),

    /** The acting player's head, so the row shows whose hands it is in. */
    ACTOR_HEAD(Material.PLAYER_HEAD),

    /** A plain head: no actor, or the viewer may not see who it was. */
    DEFAULT_HEAD(Material.PLAYER_HEAD),

    /** Destroyed by fire or lava. */
    BURNED(Material.LAVA_BUCKET),

    /** Removed by a command or plugin. */
    CLEARED(Material.BARRIER),

    /** Vanished off the ground after the despawn timer. */
    DESPAWNED(Material.STRUCTURE_VOID);

    private final Material material;

    TimelineIcon(Material material) {
        this.material = material;
    }

    public Material material() {
        return material;
    }
}
