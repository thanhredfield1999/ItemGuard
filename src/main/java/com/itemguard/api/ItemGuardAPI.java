package com.itemguard.api;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

public class ItemGuardAPI {

    private final ItemGuard plugin;

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

    public ItemStack trackItem(ItemStack item, Player owner) {
        return plugin.getTrackingService().trackItem(item, owner);
    }

    public boolean shouldTrack(ItemStack item) {
        return plugin.getTrackingService().shouldTrack(item);
    }

    public int getOnlineTrackedCount() {
        return plugin.getDB().getOnlineTrackedCount();
    }
}
