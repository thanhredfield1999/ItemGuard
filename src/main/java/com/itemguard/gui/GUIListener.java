package com.itemguard.gui;

import com.itemguard.ItemGuard;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GUIListener implements Listener {

    private final ItemGuard plugin;
    private final Map<UUID, HistoryGUI> openGUIs = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerBrowserGUI> openBrowsers = new ConcurrentHashMap<>();
    private FilterChatListener filterChatListener;

    public GUIListener(ItemGuard plugin) {
        this.plugin = plugin;
    }

    public void setFilterChatListener(FilterChatListener filterChatListener) {
        this.filterChatListener = filterChatListener;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.isShiftClick()) return;

        var holder = event.getInventory().getHolder();

        // HistoryGUI
        if (holder instanceof HistoryGUI gui) {
            event.setCancelled(true);
            HistoryGUI.handleClick(event, gui);
            return;
        }

        // PlayerBrowserGUI - Player Items
        if (holder instanceof PlayerBrowserGUI browser) {
            event.setCancelled(true);
            handleBrowserClick(event, player, browser);
            return;
        }

        // MainBrowser - Player List
        if (holder instanceof PlayerBrowserGUI.MainBrowser mainBrowser) {
            event.setCancelled(true);
            handleMainBrowserClick(event, player, mainBrowser);
            return;
        }

        // Detail view
        if (holder instanceof HistoryGUI.HistoryDetailHolder) {
            event.setCancelled(true);
            HistoryGUI.handleDetailClick(event);
        }
    }

    private void handleBrowserClick(InventoryClickEvent event, Player player, PlayerBrowserGUI browser) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        var ns = new org.bukkit.NamespacedKey(browser.getPlugin(), "ig_item_idx");
        var codeKey = new org.bukkit.NamespacedKey(browser.getPlugin(), "ig_code");
        var navKey = new org.bukkit.NamespacedKey(browser.getPlugin(), "ig_nav");
        var actKey = new org.bukkit.NamespacedKey(browser.getPlugin(), "ig_action");

        // Nav buttons
        var nav = pdc.get(navKey, org.bukkit.persistence.PersistentDataType.STRING);
        if (nav != null) {
            if ("prev".equals(nav)) {
                browser.openPage(browser.getCurrentPage() - 1);
            } else if ("next".equals(nav)) {
                browser.openPage(browser.getCurrentPage() + 1);
            }
            return;
        }

        // Close button
        int slot = event.getSlot();
        if (slot == 49) {
            player.closeInventory();
            return;
        }

        // Filter button
        var action = pdc.get(actKey, org.bukkit.persistence.PersistentDataType.STRING);
        if (action != null && "filter".equals(action)) {
            player.closeInventory();
            if (filterChatListener != null) {
                filterChatListener.requestFilter(player, browser.getTargetPlayerUuid(), browser.getTargetPlayerName());
            }
            return;
        }

        // Item icon - view history
        var idxVal = pdc.get(ns, org.bukkit.persistence.PersistentDataType.INTEGER);
        var codeVal = pdc.get(codeKey, org.bukkit.persistence.PersistentDataType.STRING);
        if (idxVal != null && codeVal != null) {
            List<com.itemguard.data.ItemHistory> histories = browser.getPlugin().getDB().getHistory(codeVal, 100);
            if (!histories.isEmpty()) {
                HistoryGUI gui = new HistoryGUI(browser.getPlugin(), player, codeVal, histories);
                openGUIs.put(player.getUniqueId(), gui);
                gui.open();
            } else {
                player.sendMessage("§7Khong co lich su cho item nay.");
            }
        }
    }

    private void handleMainBrowserClick(InventoryClickEvent event, Player player, PlayerBrowserGUI.MainBrowser mainBrowser) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        var navKey = new org.bukkit.NamespacedKey(mainBrowser.getPlugin(), "ig_main_nav");
        var playerKey = new org.bukkit.NamespacedKey(mainBrowser.getPlugin(), "ig_main_player");

        // Nav
        var nav = pdc.get(navKey, org.bukkit.persistence.PersistentDataType.STRING);
        if (nav != null) {
            if ("prev".equals(nav)) {
                mainBrowser.openPage(mainBrowser.getCurrentPage() - 1);
            } else if ("next".equals(nav)) {
                mainBrowser.openPage(mainBrowser.getCurrentPage() + 1);
            }
            return;
        }

        // Close
        int slot = event.getSlot();
        if (slot == 49) {
            player.closeInventory();
            return;
        }

        // Player head
        var uuidStr = pdc.get(playerKey, org.bukkit.persistence.PersistentDataType.STRING);
        if (uuidStr != null) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
                String targetName = target.getName() != null ? target.getName() : uuidStr;

                List<com.itemguard.data.ItemData> items = mainBrowser.getPlugin().getDB().getItemsByPlayer(uuid);
                if (items.isEmpty()) {
                    player.sendMessage("§7Nguoi choi nay chua co item nao duoc theo doi.");
                    return;
                }

                player.closeInventory();
                PlayerBrowserGUI.openPlayerItems(player, targetName, uuid, items);
            } catch (Exception ignored) {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (PlayerBrowserGUI.isOurGUI(event.getInventory())) {
            event.setCancelled(true);
        }
        if (event.getInventory().getHolder() instanceof HistoryGUI gui) {
            HistoryGUI.handleDrag(event, gui);
        }
    }

    public void registerOpenGUI(Player player, HistoryGUI gui) {
        openGUIs.put(player.getUniqueId(), gui);
    }

    public void registerOpenBrowser(Player player, PlayerBrowserGUI gui) {
        openBrowsers.put(player.getUniqueId(), gui);
    }

    public void unregisterOpenGUI(Player player) {
        openGUIs.remove(player.getUniqueId());
    }

    public boolean hasOpenGUI(Player player) {
        return openGUIs.containsKey(player.getUniqueId());
    }

    public PlayerBrowserGUI getPendingBrowser(Player player) {
        return openBrowsers.remove(player.getUniqueId());
    }

    public void openBrowserPendingFilter(Player player, String[] args) {
        // /ig browser - open main browser
        // /ig browser <player> - open player items directly
        if (args.length <= 1) {
            PlayerBrowserGUI.MainBrowser.openMainBrowser(player);
            return;
        }

        // /ig browser <playerName>
        String targetName = args[1];
        org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayer(targetName);
        if (target != null) {
            java.util.List<com.itemguard.data.ItemData> items = plugin.getDB().getItemsByPlayer(target.getUniqueId());
            if (items.isEmpty()) {
                player.sendMessage(plugin.getMessages().getRaw("search-empty"));
                return;
            }
            PlayerBrowserGUI.openPlayerItems(player, target.getName(), target.getUniqueId(), items);
        } else {
            player.sendMessage(plugin.getMessages().getRaw("player-not-found", java.util.Map.of("player", targetName)));
        }
    }

    public void handleFilterInput(Player player, String input) {
    }
}
