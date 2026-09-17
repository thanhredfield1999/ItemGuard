package com.itemguard.gui;

import com.itemguard.ItemGuard;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
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
    private final GuiClickPolicy clickPolicy = new GuiClickPolicy();
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
        var holder = event.getInventory().getHolder();
        boolean itemGuardGui = holder instanceof HistoryGUI
            || holder instanceof PlayerBrowserGUI
            || holder instanceof PlayerBrowserGUI.MainBrowser
            || holder instanceof HistoryGUI.HistoryDetailHolder;
        GuiClickAction action = clickPolicy.resolve(itemGuardGui, event.isShiftClick());
        if (action == GuiClickAction.IGNORE) return;
        event.setCancelled(true);
        if (action == GuiClickAction.CANCEL_ONLY) return;

        // HistoryGUI
        if (holder instanceof HistoryGUI gui) {
            HistoryGUI.handleClick(event, gui);
            return;
        }

        // PlayerBrowserGUI - Player Items
        if (holder instanceof PlayerBrowserGUI browser) {
            handleBrowserClick(event, player, browser);
            return;
        }

        // MainBrowser - Player List
        if (holder instanceof PlayerBrowserGUI.MainBrowser mainBrowser) {
            handleMainBrowserClick(event, player, mainBrowser);
            return;
        }

        // Detail view
        if (holder instanceof HistoryGUI.HistoryDetailHolder) {
            HistoryGUI.handleDetailClick(event);
        }
    }

    private void handleBrowserClick(InventoryClickEvent event, Player player, PlayerBrowserGUI browser) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        var meta = clicked.getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();
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
            browser.getPlugin().getDB().getHistoryAsync(codeVal, 100)
                .whenComplete((histories, failure) -> UiMainThreadHandoff.dispatch(
                    browser.getPlugin(),
                    () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (failure != null) {
                            reportReadFailure(player, "lịch sử vật phẩm", failure);
                            return;
                        }
                        if (histories.isEmpty()) {
                            player.sendMessage("§7Không có lịch sử cho vật phẩm này.");
                            return;
                        }
                        HistoryGUI gui = new HistoryGUI(
                            browser.getPlugin(), player, codeVal, histories,
                            browser.getTargetPlayerUuid(), browser.getTargetPlayerName());
                        openGUIs.put(player.getUniqueId(), gui);
                        gui.open();
                    }
                ));
        }
    }

    private void handleMainBrowserClick(InventoryClickEvent event, Player player, PlayerBrowserGUI.MainBrowser mainBrowser) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        var meta = clicked.getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();
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

                mainBrowser.getPlugin().getDB().getItemsByPlayerAsync(uuid)
                    .whenComplete((items, failure) -> UiMainThreadHandoff.dispatch(
                        mainBrowser.getPlugin(),
                        () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (failure != null) {
                                reportReadFailure(player, "danh sách vật phẩm", failure);
                                return;
                            }
                            if (items.isEmpty()) {
                                player.sendMessage("§7Người chơi này chưa có vật phẩm nào được theo dõi.");
                                return;
                            }
                            player.closeInventory();
                            PlayerBrowserGUI.openPlayerItems(player, targetName, uuid, items);
                        }
                    ));
            } catch (IllegalArgumentException invalidUuid) {
                plugin.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Invalid player UUID stored in ItemGuard GUI: " + uuidStr,
                    invalidUuid
                );
                player.sendMessage("§e§l[ItemGuard] §cDữ liệu người chơi trong giao diện không hợp lệ.");
            }
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        Object holder = event.getInventory().getHolder();
        if (holder instanceof HistoryGUI gui) {
            openGUIs.remove(playerUuid, gui);
        } else if (holder instanceof HistoryGUI.HistoryDetailHolder) {
            openGUIs.remove(playerUuid);
        } else if (holder instanceof PlayerBrowserGUI browser) {
            openBrowsers.remove(playerUuid, browser);
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

    public void releasePlayer(UUID playerUuid) {
        openGUIs.remove(playerUuid);
        openBrowsers.remove(playerUuid);
        if (filterChatListener != null) {
            filterChatListener.releasePlayer(playerUuid);
        }
    }

    public void clearSessions() {
        openGUIs.clear();
        openBrowsers.clear();
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
        if (args.length == 0) {
            PlayerBrowserGUI.MainBrowser.openMainBrowser(player);
            return;
        }

        // /ig browser <playerName>
        String targetName = args[0];
        Player target = org.bukkit.Bukkit.getPlayer(targetName);
        if (target != null) {
            UUID targetUuid = target.getUniqueId();
            String resolvedName = target.getName();
            plugin.getDB().getItemsByPlayerAsync(targetUuid)
                .whenComplete((items, failure) -> UiMainThreadHandoff.dispatch(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (failure != null) {
                        reportReadFailure(player, "danh sách vật phẩm", failure);
                        return;
                    }
                    if (items.isEmpty()) {
                        player.sendMessage(plugin.getMessages().getRaw("search-empty"));
                        return;
                    }
                    PlayerBrowserGUI.openPlayerItems(
                        player,
                        resolvedName,
                        targetUuid,
                        items
                    );
                }));
        } else {
            player.sendMessage(plugin.getMessages().getRaw("player-not-found", java.util.Map.of("player", targetName)));
        }
    }

    private void reportReadFailure(Player player, String operation, Throwable failure) {
        plugin.getLogger().log(
            java.util.logging.Level.SEVERE,
            "Failed to load ItemGuard " + operation,
            failure
        );
        player.sendMessage("§e§l[ItemGuard] §cKhông thể tải " + operation + " lúc này.");
    }

    public void handleFilterInput(Player player, String input) {
    }
}
