package com.itemguard.api;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemData;
import com.itemguard.tracking.CraftItemStackPhysicalHandle;
import com.itemguard.tracking.InventoryPhysicalSourcePolicy;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

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

    public Optional<ItemData> getTrackedItem(String code) {
        return plugin.getTrackingService().getTrackedItem(code);
    }

    public Optional<ItemData> getTrackedItemByUuid(UUID uuid) {
        return plugin.getTrackingService().getTrackedItemByUuid(uuid);
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
