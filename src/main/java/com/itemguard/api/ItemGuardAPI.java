package com.itemguard.api;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemData;
import com.itemguard.tracking.CraftItemStackPhysicalHandle;
import com.itemguard.tracking.InventoryPhysicalSourcePolicy;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ItemGuardAPI {

    private final ItemGuard plugin;
    private final InventoryPhysicalSourcePolicy physicalSourcePolicy =
        new InventoryPhysicalSourcePolicy();

    public ItemGuardAPI(ItemGuard plugin) {
        this.plugin = plugin;
    }

    public static ItemGuardAPI getInstance() {
        return new ItemGuardAPI(ItemGuard.getInstance());
    }

    /**
     * Blocking lookup of a tracked item by public code.
     *
     * <p>This call waits for the item database on the calling thread, and on MySQL that means waiting
     * for a network round trip — calling it from the server thread stalls the tick loop. Use
     * {@link #getTrackedItemAsync(String)} there. This form stays because 1.0.0 shipped it; it exists
     * for callers that are already off-thread.
     *
     * @deprecated use {@link #getTrackedItemAsync(String)} on the server thread
     */
    @Deprecated(forRemoval = false)
    public Optional<ItemData> getTrackedItem(String code) {
        return plugin.getTrackingService().getTrackedItem(code);
    }

    /**
     * Blocking lookup of a tracked item by identity UUID. Same rule: the server thread must call
     * {@link #getTrackedItemByUuidAsync(UUID)} instead of blocking on the database here.
     *
     * @deprecated use {@link #getTrackedItemByUuidAsync(UUID)} on the server thread
     */
    @Deprecated(forRemoval = false)
    public Optional<ItemData> getTrackedItemByUuid(UUID uuid) {
        return plugin.getTrackingService().getTrackedItemByUuid(uuid);
    }

    /**
     * Asynchronous lookup by public code: the returned future completes when the database answers, and
     * nothing waits on the caller's thread. Completion arrives on the database thread, so touch Bukkit
     * state through the scheduler.
     */
    public CompletableFuture<Optional<ItemData>> getTrackedItemAsync(String code) {
        return plugin.getTrackingService().getTrackedItemAsync(code);
    }

    /**
     * Asynchronous lookup by identity UUID. Completes off the server thread; hand any Bukkit work back
     * through the scheduler.
     */
    public CompletableFuture<Optional<ItemData>> getTrackedItemByUuidAsync(UUID uuid) {
        return plugin.getTrackingService().getTrackedItemByUuidAsync(uuid);
    }

    public boolean isItemTracked(ItemStack item) {
        return plugin.getTrackingService().hasCode(item);
    }

    public String getItemCode(ItemStack item) {
        return plugin.getTrackingService().getCodeFromItem(item);
    }

    public UUID getItemUuid(ItemStack item) {
        return plugin.getTrackingService().getItemUuidFromItem(item);
    }

    @Deprecated(forRemoval = false)
    public ItemStack trackItem(ItemStack item, Player owner) {
        trackPlayerItemAsync(item, owner);
        return item;
    }

    public boolean trackPlayerItemAsync(ItemStack item, Player owner) {
        if (item == null || owner == null) {
            return false;
        }
        Object expectedPhysicalHandle = CraftItemStackPhysicalHandle.extract(item);
        if (expectedPhysicalHandle == null) {
            return false;
        }
        for (int slot = 0; slot < owner.getInventory().getSize(); slot++) {
            if (physicalSourcePolicy.samePhysicalHandle(
                expectedPhysicalHandle,
                CraftItemStackPhysicalHandle.extract(owner.getInventory().getItem(slot))
            )) {
                return plugin.getTrackingService().requestPlayerSlotTag(owner, slot);
            }
        }
        return false;
    }

    public boolean shouldTrack(ItemStack item) {
        return plugin.getTrackingService().shouldTrack(item);
    }

    public int getOnlineTrackedCount() {
        return plugin.getDB().getOnlineTrackedCount();
    }
}
