package com.itemguard.lite;

import com.itemguard.tracking.ContainerTransfer;
import com.itemguard.tracking.PositionSource;
import com.itemguard.tracking.TransferPositionPolicy;

import java.util.Locale;

/**
 * Decides whether left-clicking a timeline row may teleport the viewer to that container.
 *
 * <p>A chest position is precisely what a raider wants, so this is fail-closed and staff-only.
 * Permission is checked before anything else, so a refusal message can never be used to work out
 * whether a given item is stored somewhere.
 *
 * <p>Only rows whose position is a container are targets. A row recording where a player stood is
 * both useless as a destination and a leak of that player's movements.
 */
public final class JumpGate {

    private JumpGate() {
    }

    public static JumpDecision evaluate(JumpRequest request) {
        if (request == null) {
            return JumpDecision.refuse(JumpRefusal.NO_POSITION);
        }
        if (!request.hasPermission()) {
            return JumpDecision.refuse(JumpRefusal.NO_PERMISSION);
        }
        if (!request.maySeeRow()) {
            // If the row's details were hidden from this viewer, so is its position.
            return JumpDecision.refuse(JumpRefusal.ROW_REDACTED);
        }
        if (!targetsAContainer(request.action())) {
            return JumpDecision.refuse(JumpRefusal.NOT_A_CONTAINER);
        }
        if (!request.hasPosition()) {
            return JumpDecision.refuse(JumpRefusal.NO_POSITION);
        }
        if (!request.worldLoaded()) {
            return JumpDecision.refuse(JumpRefusal.WORLD_NOT_LOADED);
        }
        if (!request.hasSafeSpot()) {
            return JumpDecision.refuse(JumpRefusal.NO_SAFE_SPOT);
        }
        return JumpDecision.allow();
    }

    /**
     * Reuses the same rule that decided which position was recorded, so the two cannot disagree: a
     * row only has a container position if its transfer names a container in the world.
     */
    private static boolean targetsAContainer(String action) {
        if (action == null) {
            return false;
        }
        String normalised = action.trim().toUpperCase(Locale.ROOT);
        for (ContainerTransfer transfer : ContainerTransfer.values()) {
            if (transfer.action().equals(normalised)) {
                return TransferPositionPolicy.sourceFor(transfer) == PositionSource.CONTAINER;
            }
        }
        return false;
    }
}
