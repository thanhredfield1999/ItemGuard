package com.itemguard.custody;

import java.util.Locale;
import java.util.Set;

/**
 * Which recorded actions mean a player now holds the item.
 *
 * <p>Deliberately an allowlist. Custody is the basis of the "changed hands" count and of the list of
 * players who have carried an item, so a newly added action must be reviewed before it can affect
 * either. Releasing actions such as dropping, storing or dying are not listed: they mean the item
 * left someone's hands, and custody only moves when the next player actually takes it.
 */
public final class CustodyActionPolicy {

    private static final Set<String> CLAIMS = Set.of(
        "PICKUP",          // taken off the ground into a player's inventory
        "CONTAINER_TAKE",  // taken out of a chest or similar placed in the world
        "SHULKER_TAKE",    // taken out of a shulker box placed in the world
        "SPAWN"            // first time the item was tracked, which starts the chain
    );

    // Deliberately absent, each for a stated reason:
    //   CONTAINER_PUT, SHULKER_PUT, ENDERCHEST_PUT, CARRIED_CONTAINER_PUT — storing is releasing, not claiming.
    //   ENDERCHEST_TAKE — an ender chest is private to one player, so the holder never changed.
    //     Counting it would let a single player inflate their own chain with no second party.
    //   CARRIED_CONTAINER_TAKE — a shulker in your own inventory is still your own hands.
    //   INVENTORY_MOVE — moving between your own slots.
    //   Hopper and other machine transfers are never written at all, so no player can be named as
    //     the actor for an automated move.

    private CustodyActionPolicy() {
    }

    public static boolean claimsCustody(String action) {
        if (action == null) {
            return false;
        }
        return CLAIMS.contains(action.trim().toUpperCase(Locale.ROOT));
    }
}
