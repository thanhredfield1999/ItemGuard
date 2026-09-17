package com.itemguard.restore;

import com.itemguard.data.ItemHistory;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads an identity's restore-relevant state out of its recorded history.
 *
 * <p>No schema change is needed: a {@code RESTORED} row is itself the record that a restore already
 * happened, and the loss rows record the destruction. Deriving state from history means the audit
 * trail and the gate can never disagree with each other.
 *
 * <p>Fail-closed throughout. An unrecognised latest action yields {@link IdentityState#UNKNOWN},
 * which refuses a restore, because guessing is how a false positive becomes a duplicate.
 */
public final class RestoreStateReader {

    /** Actions meaning a player or a container currently holds it. */
    private static final Set<String> HOLDING = Set.of(
        "PICKUP", "SPAWN", "INVENTORY_MOVE", "INVENTORY_DRAG", "USE", "RESTORED",
        "CONTAINER_TAKE", "CONTAINER_PUT",
        "ENDERCHEST_TAKE", "ENDERCHEST_PUT",
        "CARRIED_CONTAINER_TAKE", "CARRIED_CONTAINER_PUT"
    );

    private RestoreStateReader() {
    }

    /**
     * @param newestFirst the identity's history, newest row first, as the repository returns it
     */
    public static State read(List<ItemHistory> newestFirst) {
        if (newestFirst == null || newestFirst.isEmpty()) {
            return new State(IdentityState.UNKNOWN, LossReason.UNKNOWN, false);
        }
        boolean everRestored = newestFirst.stream()
            .map(ItemHistory::getAction)
            .filter(java.util.Objects::nonNull)
            .anyMatch(action -> "RESTORED".equalsIgnoreCase(action.trim()));

        String latest = newestFirst.get(0).getAction();
        String action = latest == null ? "" : latest.trim().toUpperCase(Locale.ROOT);

        LossReason reason = LossReason.fromCause(action);
        if (reason.confirmsDestruction()) {
            return new State(IdentityState.DESTROYED, reason, everRestored);
        }
        if ("DROP".equals(action)) {
            return new State(IdentityState.DROPPED, LossReason.UNKNOWN, everRestored);
        }
        if ("DEATH".equals(action)) {
            // The body dropped it; it is on the ground until somebody takes it.
            return new State(IdentityState.DROPPED, LossReason.UNKNOWN, everRestored);
        }
        if (HOLDING.contains(action)) {
            return new State(IdentityState.HELD, LossReason.UNKNOWN, everRestored);
        }
        return new State(IdentityState.UNKNOWN, LossReason.UNKNOWN, everRestored);
    }

    /**
     * @param identityState  what the records say about the identity now
     * @param lossReason     why it is gone, when it is
     * @param alreadyRestored whether a restore is already on record
     */
    public record State(IdentityState identityState, LossReason lossReason, boolean alreadyRestored) {}
}
