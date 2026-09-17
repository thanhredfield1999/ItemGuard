package com.itemguard.lite;

import java.util.Locale;
import java.util.Map;

/**
 * Chooses the icon for one timeline row.
 *
 * <p>The row's subject decides the icon. "Put into a chest" is about the chest, so it shows a chest;
 * an ender chest row shows an ender chest. Rows about a player holding, dropping or losing the item
 * show that player's head.
 *
 * <p>Skins are resolved by setting the head's owning player, never by fetching a texture over HTTP
 * while rendering a GUI: on the main thread that stalls every player on the server.
 */
public final class TimelineIconPolicy {

    /** Actions whose subject is a place, so the icon is that place and redaction cannot change it. */
    private static final Map<String, TimelineIcon> PLACES = Map.ofEntries(
        Map.entry("CONTAINER_PUT", TimelineIcon.CHEST),
        Map.entry("CONTAINER_TAKE", TimelineIcon.CHEST),
        Map.entry("SHULKER_PUT", TimelineIcon.SHULKER_BOX),
        Map.entry("SHULKER_TAKE", TimelineIcon.SHULKER_BOX),
        Map.entry("ENDERCHEST_PUT", TimelineIcon.ENDER_CHEST),
        Map.entry("ENDERCHEST_TAKE", TimelineIcon.ENDER_CHEST),
        Map.entry("CARRIED_CONTAINER_PUT", TimelineIcon.SHULKER_BOX),
        Map.entry("CARRIED_CONTAINER_TAKE", TimelineIcon.SHULKER_BOX),
        Map.entry("BURNED", TimelineIcon.BURNED),
        Map.entry("CLEARED", TimelineIcon.CLEARED),
        Map.entry("DESPAWNED", TimelineIcon.DESPAWNED)
    );

    /** Actions whose subject is a player, so the icon is that player. */
    private static final java.util.Set<String> ACTORS = java.util.Set.of(
        "PICKUP",
        "DROP",
        "DEATH",
        "INVENTORY_MOVE",
        "INVENTORY_DRAG",
        "USE",
        "SPAWN",
        "RESTORED"
    );

    private TimelineIconPolicy() {
    }

    /**
     * @param action      the recorded action
     * @param hasActor    whether a usable acting player was recorded
     * @param maySeeActor whether this viewer may know who the actor was
     */
    public static TimelineIcon iconFor(String action, boolean hasActor, boolean maySeeActor) {
        String normalised = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        TimelineIcon place = PLACES.get(normalised);
        if (place != null) {
            return place;
        }
        if (ACTORS.contains(normalised) && hasActor && maySeeActor) {
            return TimelineIcon.ACTOR_HEAD;
        }
        return TimelineIcon.DEFAULT_HEAD;
    }
}
