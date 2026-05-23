package com.itemguard.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GUIListener implements Listener {

    private final Map<UUID, HistoryGUI> openGUIs = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof HistoryGUI gui)) return;

        event.setCancelled(true);

        if (event.isShiftClick()) return;

        HistoryGUI.handleClick(event, gui);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof HistoryGUI gui)) return;

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClickDetail(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getInventory().getType() == InventoryType.CHEST) {
            String title = event.getView().getTitle();
            if (title.startsWith("§8§lChi Tiet #")) {
                HistoryGUI.handleDetailClick(event);
            }
        }
    }

    public void registerOpenGUI(Player player, HistoryGUI gui) {
        openGUIs.put(player.getUniqueId(), gui);
    }

    public void unregisterOpenGUI(Player player) {
        openGUIs.remove(player.getUniqueId());
    }

    public boolean hasOpenGUI(Player player) {
        return openGUIs.containsKey(player.getUniqueId());
    }
}
