package com.itemguard.restore;

/**
 * Decides whether a destroyed identity may be handed back to a player.
 *
 * <p>Specification: {@code docs/design/2026-09-12-loss-and-restore.md}. Handing items back is the
 * easiest way to introduce duplication into an anti-duplication plugin, so this is fail-closed: every
 * precondition must hold, and anything unrecognised refuses.
 *
 * <p>Checks run in a fixed order. Permission comes first so that someone without it cannot learn
 * anything about an identity's state from the refusal message.
 */
public final class RestoreGate {

    private RestoreGate() {
    }

    public static RestoreDecision evaluate(RestoreRequest request) {
        if (request == null) {
            return RestoreDecision.refuse(RestoreRefusal.UNKNOWN_IDENTITY);
        }
        if (!request.hasPermission()) {
            return RestoreDecision.refuse(RestoreRefusal.NO_PERMISSION);
        }
        if (request.state() == null || request.state() == IdentityState.UNKNOWN) {
            return RestoreDecision.refuse(RestoreRefusal.UNKNOWN_IDENTITY);
        }
        if (request.alreadyRestored()) {
            // One restore per identity, ever. Otherwise a repeatedly "lost" item is a free tap.
            return RestoreDecision.refuse(RestoreRefusal.ALREADY_RESTORED);
        }
        if (request.state() != IdentityState.DESTROYED) {
            return RestoreDecision.refuse(RestoreRefusal.NOT_DESTROYED);
        }
        if (request.lossConfirmedAtEpoch() <= 0L) {
            // Absence must be proven by a completed sweep. An unloaded chunk hides intact items.
            return RestoreDecision.refuse(RestoreRefusal.NOT_CONFIRMED_LOST);
        }
        if (request.presentInLatestEpoch()) {
            return RestoreDecision.refuse(RestoreRefusal.SEEN_IN_LATEST_EPOCH);
        }
        if (request.lossConfirmedAtEpoch() < request.latestCompletedEpoch()) {
            // The world has been swept again since the loss was confirmed; reconfirm against it.
            return RestoreDecision.refuse(RestoreRefusal.STALE_CONFIRMATION);
        }
        return RestoreDecision.allow();
    }
}
