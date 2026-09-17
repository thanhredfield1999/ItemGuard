package com.itemguard.tracking;

/**
 * Decides whether an item that has just arrived in a player's inventory should be tagged now
 * rather than at the next periodic sweep.
 *
 * <p>Before this existed, the only immediate tagging path was picking an item up off the
 * ground. An operator running {@code /give} got an item with no ID, and {@code /ig check} said
 * so, until {@code InventoryScanTask} came round 600 ticks later. Throwing the item down and
 * picking it back up produced an ID instantly, which made the behaviour look arbitrary.
 *
 * <p>The real cost of that window is detection, not cosmetics. Two identical items issued
 * inside the same window are both untagged, so the sweep gives each a fresh distinct identity
 * and the plugin records two legitimate items instead of one duplicated one. Narrowing the
 * window narrows what can be created without the tool noticing.
 */
public final class InHandTaggingPolicy {

    /**
     * @param trackable      whether the item is eligible for an identity at all
     * @param alreadyTagged  whether it already carries a code/uuid
     * @return true when the item should be tagged at this moment
     */
    public boolean shouldTagNow(boolean trackable, boolean alreadyTagged) {
        // Re-tagging is the dangerous direction: it would mint a second identity for a single
        // physical object and make one item look like two.
        return trackable && !alreadyTagged;
    }
}
