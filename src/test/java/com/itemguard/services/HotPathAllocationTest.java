package com.itemguard.services;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the allocation behaviour on the hottest path in the plugin.
 *
 * <p>{@code ContainerListener.onInventoryMoveItem} fires once per hopper transfer, every tick,
 * for every hopper on the server, and calls {@code resolveIdentityTags}. That method built two
 * {@code NamespacedKey} objects per call — immutable, identical every time, and trivially
 * cacheable. One test bot never notices; a few hundred hoppers running for hours turn it into
 * steady garbage on the main thread.
 *
 * <p>This is the one place where work scales with server activity rather than with player
 * commands, which is why it is worth pinning rather than left to judgement.
 */
class HotPathAllocationTest {

    /** Roughly one minute of 200 active hoppers at 20 ticks per second. */
    private static final int SUSTAINED_CALLS = 200 * 20 * 60;

    @Test
    @DisplayName("the cache hands back the same key instance every time")
    void keysAreReused() {
        ItemGuardKeyCache cache = new ItemGuardKeyCache(
            name -> new NamespacedKey("itemguard", name));

        NamespacedKey first = cache.code();
        assertSame(first, cache.code(), "code key was rebuilt instead of reused");
        assertSame(cache.itemUuid(), cache.itemUuid(), "uuid key was rebuilt instead of reused");
    }

    @Test
    @DisplayName("sustained hopper traffic allocates no additional keys")
    void sustainedTrafficAllocatesNothing() {
        // Counts real constructions, so a regression that reintroduces per-call allocation
        // fails loudly instead of quietly costing frames on someone's server.
        int[] built = {0};
        ItemGuardKeyCache cache = new ItemGuardKeyCache(name -> {
            built[0]++;
            return new NamespacedKey("itemguard", name);
        });

        for (int i = 0; i < SUSTAINED_CALLS; i++) {
            cache.code();
            cache.itemUuid();
        }

        if (built[0] != 2) {
            throw new AssertionError(
                "expected exactly 2 keys ever built, but built " + built[0]
                    + " across " + SUSTAINED_CALLS + " calls");
        }
    }
}
