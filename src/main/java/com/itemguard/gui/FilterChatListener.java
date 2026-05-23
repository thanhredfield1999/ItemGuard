package com.itemguard.gui;

import com.itemguard.ItemGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FilterChatListener implements Listener {

    private final ItemGuard plugin;
    private final Map<UUID, FilterRequest> pendingFilters = new ConcurrentHashMap<>();

    public FilterChatListener(ItemGuard plugin) {
        this.plugin = plugin;
    }

    public void requestFilter(Player player, UUID targetUuid, String targetName) {
        pendingFilters.put(player.getUniqueId(), new FilterRequest(targetUuid, targetName));
        player.sendMessage("§e§l[ItemGuard] §7Nhap loai item muon loc (VD: fishing_rod, sword, bow, helmet):");
        player.sendMessage("§7Hoac nhap §e* §7de xem tat ca. Nhap §cHuy §7de huy.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        FilterRequest req = pendingFilters.remove(player.getUniqueId());
        if (req == null) return;

        event.setCancelled(true);
        String input = event.getMessage().trim();

        if (input.equalsIgnoreCase("Huy")) {
            player.sendMessage("§7Da huy yeu cau loc.");
            java.util.List<com.itemguard.data.ItemData> allItems = plugin.getDB().getItemsByPlayer(req.uuid);
            PlayerBrowserGUI.openPlayerItems(player, req.name, req.uuid, allItems);
            return;
        }

        String filter = input.equals("*") ? null : input;
        java.util.List<com.itemguard.data.ItemData> allItems = plugin.getDB().getItemsByPlayer(req.uuid);

        // InventoryOpenEvent must be triggered synchronously, so schedule on main thread
        Bukkit.getScheduler().runTask(plugin, () ->
            PlayerBrowserGUI.openPlayerItemsFiltered(player, req.name, req.uuid, allItems, filter));
    }

    private record FilterRequest(UUID uuid, String name) {}
}
