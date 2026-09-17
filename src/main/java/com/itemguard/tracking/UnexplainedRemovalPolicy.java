package com.itemguard.tracking;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a tracked item leaving an inventory was accounted for.
 *
 * <p>Matching the text of {@code /clear} is too weak: aliases exist, other plugins remove items, and
 * {@code Player.performCommand} does not fire the command event at all. The reliable signal is the
 * disappearance: if the last recorded action says the player was holding the item and it is suddenly
 * gone, something other than the player removed it.
 *
 * <p>Explained departures are an allowlist, so a newly added action is reported as unexplained until
 * somebody classifies it. Over-reporting a loss is visible and fixable; missing one is silent.
 */
public final class UnexplainedRemovalPolicy {

    /** Actions that already account for the item leaving the inventory. */
    private static final Set<String> EXPLAINED = Set.of(
        // The player put it somewhere.
        "DROP", "CONTAINER_PUT", "SHULKER_PUT", "ENDERCHEST_PUT", "CARRIED_CONTAINER_PUT",
        // It left with the player's body or was consumed.
        "DEATH", "USE",
        // A loss is already on record; do not report it twice.
        "CLEARED", "BURNED", "DESPAWNED", "VOID", "RESTORED"
    );

    /**
     * @param lastRecordedAction the item's most recent action, or null if nothing is on record
     */
    public boolean isUnexplained(String lastRecordedAction) {
        if (lastRecordedAction == null || lastRecordedAction.isBlank()) {
            return true;
        }
        return !EXPLAINED.contains(lastRecordedAction.trim().toUpperCase(Locale.ROOT));
    }
}
