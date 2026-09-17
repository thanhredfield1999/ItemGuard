package com.itemguard.tracking;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The set of identities already reconciled must not grow without limit.
 *
 * <p>It holds one string per unique tracked item seen since startup, and nothing ever removed
 * an entry. On a server that runs for weeks and sees a steady stream of new enchanted gear,
 * that is a slow, real memory leak — invisible in tests, invisible in a short runtime fixture,
 * and only noticeable as an OOM after a long uptime. Exactly the class of defect a 780-test
 * suite cannot catch.
 *
 * <p>The cap is generous on purpose. Evicting an entry is harmless: readiness is recomputed
 * from the item's own tags on the next interaction, so the worst case is one extra
 * reconciliation for an item nobody has touched in a very long time.
 */
class IdentityReadinessBoundTest {

    @Test
    @DisplayName("the ready set stays bounded across a long uptime")
    void readySetIsBounded() {
        BoundedIdentitySet set = new BoundedIdentitySet(10_000);

        // Stands in for months of a busy server minting new tracked items.
        for (int i = 0; i < 250_000; i++) {
            set.add("code" + i + ":uuid" + i);
        }

        assertTrue(set.size() <= 10_000,
            "ready set grew to " + set.size() + " entries; it is unbounded");
    }

    @Test
    @DisplayName("recently used identities survive eviction")
    void recentEntriesSurvive() {
        // Eviction must drop the coldest entries, not arbitrary ones — an item being actively
        // moved around should not lose its readiness while idle items keep theirs.
        BoundedIdentitySet set = new BoundedIdentitySet(100);
        for (int i = 0; i < 100; i++) {
            set.add("old" + i);
        }
        set.add("hot");
        for (int i = 0; i < 200; i++) {
            set.contains("hot");          // keep it warm
            set.add("filler" + i);
        }

        assertTrue(set.contains("hot"), "an actively used identity was evicted");
    }

    @Test
    @DisplayName("membership still works exactly as before under the cap")
    void membershipUnchanged() {
        BoundedIdentitySet set = new BoundedIdentitySet(10);
        set.add("a");
        assertTrue(set.contains("a"));
        assertTrue(!set.contains("b"));
    }
}
