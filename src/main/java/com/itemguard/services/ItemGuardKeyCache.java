package com.itemguard.services;

import java.util.function.Function;
import org.bukkit.NamespacedKey;

/**
 * Holds the two {@link NamespacedKey} instances ItemGuard reads on every tracked item.
 *
 * <p>Both keys are immutable and identical for the lifetime of the plugin, but they were being
 * constructed on each call inside {@code resolveIdentityTags} — which runs on the hopper
 * transfer path, once per hopper per tick. Building them once removes that allocation from the
 * busiest code in the plugin without changing a single behaviour.
 *
 * <p>The constructor takes a factory rather than the plugin so this stays testable without a
 * running server; production passes {@code plugin::getNamespacedKey}.
 */
public final class ItemGuardKeyCache {

    private final NamespacedKey code;
    private final NamespacedKey itemUuid;

    public ItemGuardKeyCache(Function<String, NamespacedKey> factory) {
        this.code = factory.apply(ItemTrackingService.KEY_CODE);
        this.itemUuid = factory.apply(ItemTrackingService.KEY_ITEM_UUID);
    }

    public NamespacedKey code() {
        return code;
    }

    public NamespacedKey itemUuid() {
        return itemUuid;
    }
}
