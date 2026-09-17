package com.itemguard.gui;

import com.itemguard.ItemGuard;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FilterChatListener implements Listener {

    /**
     * How long a pending filter query stays armed.
     *
     * <p>Without an expiry the request stayed armed until the player quit: an admin who pressed
     * Filter, then went to do something else, had their next chat message — possibly hours later,
     * mid-conversation — swallowed and interpreted as a filter query. The player sees their message
     * vanish and gets a private item list instead, which is the same class of defect as a UI state
     * that never turns off.
     */
    static final long FILTER_INPUT_TTL_MS = 30_000L;

    private final ItemGuard plugin;
    private final Map<UUID, FilterRequest> pendingFilters = new ConcurrentHashMap<>();
    private final FilterInputPolicy inputPolicy = new FilterInputPolicy();

    public FilterChatListener(ItemGuard plugin) {
        this.plugin = plugin;
    }

    public void requestFilter(Player player, UUID targetUuid, String targetName) {
        pendingFilters.put(
            player.getUniqueId(),
            new FilterRequest(targetUuid, targetName, System.currentTimeMillis())
        );
        player.sendMessage("§e§l[ItemGuard] §7Nhap loai item muon loc (VD: fishing_rod, sword, bow, helmet):");
        player.sendMessage("§7Hoac nhap §e* §7de xem tat ca. Nhap §cHuy §7de huy. Het han sau "
            + (FILTER_INPUT_TTL_MS / 1_000L) + " giay.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        FilterRequest req = pendingFilters.remove(player.getUniqueId());
        if (req == null) return;
        if (expired(req.requestedAt(), System.currentTimeMillis(), FILTER_INPUT_TTL_MS)) {
            // Do not cancel: this is ordinary chat by now, not an answer to a question the player
            // has stopped looking at. The request is already removed above, so it cannot come back.
            player.sendMessage("§e§l[ItemGuard] §7Yeu cau loc da het han; tin nhan cua ban van duoc gui di.");
            return;
        }

        event.setCancelled(true);
        FilterInputAction action = inputPolicy.resolve(event.getMessage());
        plugin.getDB().getItemsByPlayerAsync(req.uuid).whenComplete((allItems, failure) ->
            UiMainThreadHandoff.dispatch(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (failure != null) {
                plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Failed to load ItemGuard browser filter data",
                    failure
                );
                player.sendMessage("§e§l[ItemGuard] §cKhông thể tải danh sách vật phẩm lúc này.");
                return;
            }
            if (action == FilterInputAction.CANCEL) {
                player.sendMessage("§7Đã hủy yêu cầu lọc.");
                PlayerBrowserGUI.openPlayerItems(player, req.name, req.uuid, allItems);
                return;
            }
            String filter = action instanceof FilterInputAction.Apply apply
                ? apply.filter()
                : null;
            PlayerBrowserGUI.openPlayerItemsFiltered(
                player,
                req.name,
                req.uuid,
                allItems,
                filter
            );
        }));
    }

    public void releasePlayer(UUID playerUuid) {
        pendingFilters.remove(playerUuid);
    }

    public void clearSessions() {
        pendingFilters.clear();
    }

    /**
     * Whether a pending filter query has gone stale. Pure so it can be tested without a server:
     * the bug it exists for was an expiry that did not exist, which is invisible to any test that
     * only ever chats immediately after pressing the button.
     */
    static boolean expired(long requestedAt, long now, long ttlMillis) {
        return now - requestedAt >= ttlMillis;
    }

    record FilterRequest(UUID uuid, String name, long requestedAt) {}
}
