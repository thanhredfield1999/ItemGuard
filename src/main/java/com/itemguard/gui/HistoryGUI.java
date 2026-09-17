package com.itemguard.gui;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class HistoryGUI implements InventoryHolder {

    public static final String GUI_TITLE = "ItemGuard - Lich Su Item";
    public static final String DETAIL_TITLE = "ItemGuard - Chi Tiet";
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int PREV_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    private static final int CLOSE_SLOT = 49;
    private static final int ITEM_START = 0;
    private static final int ITEM_END = 44;

    private final ItemGuard plugin;
    private final Player viewer;
    private final String code;
    private final List<ItemHistory> histories;
    private final int totalPages;
    private int currentPage;
    private final UUID parentPlayerUuid;
    private final String parentPlayerName;
    private final HistoryNavigationPolicy navigationPolicy = new HistoryNavigationPolicy();

    private Inventory inventory;

    public HistoryGUI(ItemGuard plugin, Player viewer, String code, List<ItemHistory> histories,
                      UUID parentPlayerUuid, String parentPlayerName) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.code = code;
        this.histories = histories;
        this.totalPages = GuiPageLayout.totalPages(histories.size());
        this.currentPage = 1;
        this.parentPlayerUuid = parentPlayerUuid;
        this.parentPlayerName = parentPlayerName;
    }

    public void open() {
        buildInventory();
        viewer.openInventory(inventory);
    }

    public void openPage(int page) {
        this.currentPage = Math.max(1, Math.min(page, totalPages));
        buildInventory();
        viewer.openInventory(inventory);
        plugin.getGuiListener().registerOpenGUI(viewer, this);
    }

    private void buildInventory() {
        inventory = Bukkit.createInventory(this, SIZE, GUI_TITLE + " §7(Trang " + currentPage + "/" + totalPages + ")");

        fillFiller();
        fillItems();
        fillNavigation();

        inventory.setItem(CLOSE_SLOT, makeCloseButton());
    }

    private void fillFiller() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.setDisplayName(" ");
        filler.setItemMeta(meta);

        for (int slot : getBorderSlots()) {
            inventory.setItem(slot, filler);
        }
    }

    private Set<Integer> getBorderSlots() {
        Set<Integer> slots = new HashSet<>();
        for (int i = 0; i < 9; i++) {
            slots.add(i);
            slots.add(i + SIZE - 9);
        }
        for (int row = 1; row < ROWS - 1; row++) {
            slots.add(row * 9);
            slots.add(row * 9 + 8);
        }
        slots.remove(PREV_SLOT);
        slots.remove(NEXT_SLOT);
        slots.remove(CLOSE_SLOT);
        return slots;
    }

    private void fillItems() {
        int start = GuiPageLayout.startIndex(currentPage);
        int end = GuiPageLayout.endIndex(currentPage, histories.size());

        for (int i = start; i < end; i++) {
            ItemHistory history = histories.get(i);
            int slot = GuiPageLayout.inventorySlot(i - start);
            inventory.setItem(slot, makeHistoryItem(history, i + 1));
        }
    }

    private void fillNavigation() {
        if (currentPage > 1) {
            inventory.setItem(PREV_SLOT, makeNavButton(Material.ARROW, "Trang truoc", "prev"));
        } else {
            ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
            ItemMeta meta = filler.getItemMeta();
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
            inventory.setItem(PREV_SLOT, filler);
        }

        if (currentPage < totalPages) {
            inventory.setItem(NEXT_SLOT, makeNavButton(Material.ARROW, "Trang sau", "next"));
        } else {
            ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
            ItemMeta meta = filler.getItemMeta();
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
            inventory.setItem(NEXT_SLOT, filler);
        }

        inventory.setItem(CLOSE_SLOT, makeCloseButton());
    }

    private ItemStack makeHistoryItem(ItemHistory history, int number) {
        Material mat = getActionMaterial(history.getAction());
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        String actionColor = getActionColor(history.getAction());
        String actionName = plugin.getMessages().getRaw("action-" + history.getAction().toLowerCase(),
            history.getAction());

        String name = actionColor + "§l" + actionName + " §7#" + number;
        meta.setDisplayName(name);

        List<String> lore = new ArrayList<>();
        lore.add("§7§m------------------------");
        lore.add("§7Nguoi choi: §f" + (history.getPlayerName() != null ? history.getPlayerName() : "Unknown"));
        lore.add("§7Vi tri: §f" + (history.getLocation() != null ? history.getLocation() : "Unknown"));
        lore.add("§7Thoi gian: §f" + history.getFormattedTimestamp());
        lore.add("§7" + history.getRelativeTime());
        lore.add("§7§m------------------------");
        lore.add("§eClick de xem chi tiet");

        meta.setLore(lore);
        item.setItemMeta(meta);

        setHistoryNBT(item, history);
        return item;
    }

    private Material getActionMaterial(String action) {
        return switch (action.toUpperCase()) {
            case "PICKUP" -> Material.LIME_DYE;
            case "DROP" -> Material.REDSTONE;
            case "DEATH" -> Material.BONE;
            case "CRAFT" -> Material.CRAFTING_TABLE;
            case "USE" -> Material.BLAZE_ROD;
            case "SPAWN" -> Material.NETHER_STAR;
            case "VOID" -> Material.BLACK_STAINED_GLASS_PANE;
            default -> {
                if (action.startsWith("CONTAINER_")) yield Material.CHEST;
                yield Material.PAPER;
            }
        };
    }

    private String getActionColor(String action) {
        return switch (action.toUpperCase()) {
            case "PICKUP", "SPAWN" -> "§a";
            case "DROP", "VOID", "DEATH" -> "§c";
            case "CRAFT" -> "§e";
            case "CONTAINER_OPEN", "CONTAINER_TAKE", "CONTAINER_PUT" -> "§b";
            case "USE" -> "§d";
            default -> "§7";
        };
    }

    private ItemStack makeNavButton(Material mat, String name, String action) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e" + name);
        List<String> lore = new ArrayList<>();
        lore.add("§7Click de " + name.toLowerCase());
        meta.setLore(lore);
        item.setItemMeta(meta);
        setNavNBT(item, action);
        return item;
    }

    private ItemStack makeCloseButton() {
        HistoryNavigationAction action = navigationPolicy.exitAction(parentPlayerUuid);
        ItemStack item = new ItemStack(action == HistoryNavigationAction.CLOSE
            ? Material.BARRIER
            : Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(action == HistoryNavigationAction.CLOSE
            ? "§cĐóng"
            : "§eQuay lại");
        List<String> lore = new ArrayList<>();
        lore.add(action == HistoryNavigationAction.CLOSE
            ? "§7Đóng lịch sử vật phẩm"
            : "§7Quay lại danh sách vật phẩm của " + parentPlayerName);
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(
            new org.bukkit.NamespacedKey(plugin, "back_action"),
            org.bukkit.persistence.PersistentDataType.STRING, action.name());

        item.setItemMeta(meta);
        return item;
    }

    private void setHistoryNBT(ItemStack item, ItemHistory history) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(
            new org.bukkit.NamespacedKey(plugin, "history_index"),
            org.bukkit.persistence.PersistentDataType.INTEGER,
            histories.indexOf(history)
        );
        item.setItemMeta(meta);
    }

    private void setNavNBT(ItemStack item, String action) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(
            new org.bukkit.NamespacedKey(plugin, "nav_action"),
            org.bukkit.persistence.PersistentDataType.STRING,
            action
        );
        item.setItemMeta(meta);
    }

    public static void handleClick(InventoryClickEvent event, HistoryGUI gui) {
        if (event.getSlot() == -999) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        Player player = (Player) event.getWhoClicked();
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        var historyIdx = meta.getPersistentDataContainer().get(
            new org.bukkit.NamespacedKey(gui.plugin, "history_index"),
            org.bukkit.persistence.PersistentDataType.INTEGER
        );

        if (historyIdx != null) {
            event.setCancelled(true);
            ItemHistory history = gui.histories.get(historyIdx);
            gui.openDetail(history);
            return;
        }

        var navAction = meta.getPersistentDataContainer().get(
            new org.bukkit.NamespacedKey(gui.plugin, "nav_action"),
            org.bukkit.persistence.PersistentDataType.STRING
        );

        if (navAction != null) {
            event.setCancelled(true);
            if ("prev".equals(navAction)) {
                gui.openPage(gui.currentPage - 1);
            } else if ("next".equals(navAction)) {
                gui.openPage(gui.currentPage + 1);
            }
            return;
        }

        var backAction = meta.getPersistentDataContainer().get(
            new org.bukkit.NamespacedKey(gui.plugin, "back_action"),
            org.bukkit.persistence.PersistentDataType.STRING);
        if (HistoryNavigationAction.CLOSE.name().equals(backAction)) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (HistoryNavigationAction.BACK_TO_PLAYER.name().equals(backAction)) {
            event.setCancelled(true);
            gui.plugin.getDB().getItemsByPlayerAsync(gui.parentPlayerUuid)
                .whenComplete((parentItems, failure) -> UiMainThreadHandoff.dispatch(
                    gui.plugin,
                    () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (failure != null) {
                            gui.plugin.getLogger().log(
                                java.util.logging.Level.SEVERE,
                                "Failed to return to ItemGuard player browser",
                                failure
                            );
                            player.sendMessage("§e§l[ItemGuard] §cKhông thể tải danh sách vật phẩm lúc này.");
                            return;
                        }
                        PlayerBrowserGUI.openPlayerItems(
                            player,
                            gui.parentPlayerName,
                            gui.parentPlayerUuid,
                            parentItems
                        );
                    }
                ));
            return;
        }

        int slot = event.getSlot();
        if (slot >= ITEM_START && slot < ITEM_END) {
            event.setCancelled(true);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void openDetail(ItemHistory history) {
        Player player = viewer;

        String title = "§8§lChi Tiet #" + (histories.indexOf(history) + 1);
        Inventory detail = Bukkit.createInventory(new HistoryDetailHolder(null, this), 9, title);

        Material mat = getActionMaterial(history.getAction());
        ItemStack header = new ItemStack(mat);
        ItemMeta meta = header.getItemMeta();
        String actionName = plugin.getMessages().getRaw("action-" + history.getAction().toLowerCase(), history.getAction());
        meta.setDisplayName("§e§l" + actionName);
        List<String> lore = new ArrayList<>();
        lore.add("§7§m------------------------");
        lore.add("§7Nguoi choi: §f" + (history.getPlayerName() != null ? history.getPlayerName() : "Unknown"));
        lore.add("§7§m------------------------");
        lore.add("§7Hanh dong: §f" + actionName);
        lore.add("§7§m------------------------");
        lore.add("§7Vi tri: §f" + (history.getLocation() != null ? history.getLocation() : "Unknown"));
        lore.add("§7The gioi: §f" + (history.getWorld() != null ? history.getWorld() : "Unknown"));
        lore.add("§7Toa do: §f" + history.getX() + ", " + history.getY() + ", " + history.getZ());
        lore.add("§7§m------------------------");
        lore.add("§7Thoi gian: §f" + history.getFormattedTimestamp());
        lore.add("§7" + history.getRelativeTime());
        if (history.getAdditionalData() != null && !history.getAdditionalData().isEmpty()) {
            lore.add("§7§m------------------------");
            lore.add("§7Du lieu them: §f" + history.getAdditionalData());
        }
        lore.add("§7§m------------------------");
        meta.setLore(lore);
        header.setItemMeta(meta);
        detail.setItem(4, header);

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName("§e Quay lai");
        back.setItemMeta(backMeta);
        detail.setItem(0, back);

        player.openInventory(detail);
    }

    public static void handleDetailClick(InventoryClickEvent event) {
        if (event.getSlot() == -999) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        Player player = (Player) event.getWhoClicked();

        if (clicked.getType() == Material.ARROW && event.getSlot() == 0) {
            event.setCancelled(true);
            var holder = event.getInventory().getHolder();
            if (holder instanceof HistoryDetailHolder detailHolder) {
                detailHolder.getParentGui().openPage(detailHolder.getParentGui().getCurrentPage());
            } else {
                player.closeInventory();
            }
        } else {
            event.setCancelled(true);
        }
    }

    // Holder so back button can return to the parent HistoryGUI
    public static class HistoryDetailHolder implements InventoryHolder {
        private final Inventory inventory;
        private final HistoryGUI parentGui;

        public HistoryDetailHolder(Inventory inventory, HistoryGUI parentGui) {
            this.inventory = inventory;
            this.parentGui = parentGui;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public HistoryGUI getParentGui() {
            return parentGui;
        }
    }

    public static void handleDrag(InventoryDragEvent event, HistoryGUI gui) {
        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < event.getInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    public Player getViewer() {
        return viewer;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getTotalPages() {
        return totalPages;
    }
}
