package com.itemguard.restore;

import java.util.Locale;
import java.util.Set;

/**
 * Decides when an identity may be declared destroyed.
 *
 * <p>The dangerous half of the loss feature: a destroyed identity can be restored, so every false
 * positive is a free duplicate. Absence alone is never evidence — an item inside an unloaded chunk or
 * a logged-out player's inventory is invisible and perfectly intact.
 *
 * <p>Confirmation needs all four: the item was absent, from a sweep that completed, that could see
 * everywhere it needed to, and the last thing that happened to the item was a release.
 */
public final class DestructionConfirmation {

    /**
     * Actions after which an item is loose in the world and therefore visible to a sweep.
     *
     * <p>An allowlist. Ender chest contents are deliberately excluded: a sweep cannot read them, so
     * their absence is expected and means nothing.
     */
    private static final Set<String> RELEASING = Set.of(
        "DROP",
        "CONTAINER_PUT",
        "SHULKER_PUT",
        "DEATH"
    );

    private DestructionConfirmation() {
    }

    public static boolean confirms(DestructionEvidence evidence) {
        if (evidence == null) {
            return false;
        }
        return evidence.absentFromSweep()
            && evidence.sweepCompleted()
            && evidence.sweepCoveredEverything()
            && isReleasing(evidence.lastAction());
    }

    public static boolean isReleasing(String action) {
        if (action == null) {
            return false;
        }
        return RELEASING.contains(action.trim().toUpperCase(Locale.ROOT));
    }
}
