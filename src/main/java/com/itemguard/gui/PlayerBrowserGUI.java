package com.itemguard.gui;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.stream.Collectors;

public class PlayerBrowserGUI implements InventoryHolder {

    public static final String ITEMS_TITLE = "§8§lItemGuard §7- Do Cua ";
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int FILTER_SLOT = 4;
    private static final int CLOSE_SLOT = 49;
    private static final int PREV_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    private static final int PLAYER_START = 10;
    private static final int PLAYER_END = 44;
    private static final int COLUMNS = 7;

    private final ItemGuard plugin;
    private final Player viewer;
    private final String targetPlayerName;
    private final UUID targetPlayerUuid;
    private final List<ItemData> items;
    private final List<ItemData> filteredItems;
    private final String filterMaterial;
    private final int totalPages;
    private int currentPage;

    private Inventory inventory;
    static final String PREFIX = "ig_";

    private PlayerBrowserGUI(ItemGuard plugin, Player viewer, String targetPlayerName, UUID targetPlayerUuid,
                              List<ItemData> items, String filterMaterial) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.targetPlayerName = targetPlayerName;
        this.targetPlayerUuid = targetPlayerUuid;
        this.items = items;
        this.filterMaterial = filterMaterial;
        this.filteredItems = filterMaterial != null
            ? items.stream().filter(i -> {
                Material m = i.getMaterial();
                if (m != null) return m.name().toLowerCase().contains(filterMaterial.toLowerCase());
                return false;
            }).collect(Collectors.toList())
            : items;
        this.totalPages = Math.max(1, (int) Math.ceil((double) filteredItems.size() / (float) COLUMNS));
        this.currentPage = 1;
    }

    public static void openMainBrowser(Player player) {
        new MainBrowser(ItemGuard.getInstance(), player).open();
    }

    public static void openPlayerItems(Player viewer, String targetName, UUID targetUuid, List<ItemData> items) {
        ItemGuard plugin = ItemGuard.getInstance();
        PlayerBrowserGUI gui = createGui(plugin, viewer, targetName, targetUuid, items, null);
        plugin.getGuiListener().registerOpenBrowser(viewer, gui);
        gui.open();
    }

    private static PlayerBrowserGUI createGui(ItemGuard plugin, Player viewer, String targetPlayerName, UUID targetPlayerUuid,
                                             List<ItemData> items, String filterMaterial) {
        return new PlayerBrowserGUI(plugin, viewer, targetPlayerName, targetPlayerUuid, items, filterMaterial);
    }

    // Called by FilterChatListener to open with a filter
    public static void openPlayerItemsFiltered(Player viewer, String targetName, UUID targetUuid,
                                               List<ItemData> items, String filter) {
        ItemGuard plugin = ItemGuard.getInstance();
        PlayerBrowserGUI gui = createGui(plugin, viewer, targetName, targetUuid, items, filter);
        plugin.getGuiListener().registerOpenBrowser(viewer, gui);
        gui.open();
    }

    public void open() {
        buildInventory();
        viewer.openInventory(inventory);
    }

    public void openPage(int page) {
        this.currentPage = Math.max(1, Math.min(page, totalPages));
        buildInventory();
        viewer.openInventory(inventory);
    }

    private void buildInventory() {
        String filterTag = filterMaterial != null ? " §7[§e" + filterMaterial + "§7]" : "";
        String title = ITEMS_TITLE + targetPlayerName + filterTag
            + " §7(Trang " + currentPage + "/" + totalPages + ")";
        inventory = Bukkit.createInventory(this, SIZE, title);

        fillBorder();
        fillPlayerHeader();
        fillItems();
        fillNavigation();
    }

    private void fillBorder() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.setDisplayName(" ");
        filler.setItemMeta(fm);

        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, filler);
            inventory.setItem(i + SIZE - 9, filler);
        }
        for (int row = 1; row < ROWS - 1; row++) {
            inventory.setItem(row * 9, filler);
            inventory.setItem(row * 9 + 8, filler);
        }
    }

    private void fillPlayerHeader() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(targetPlayerUuid));
        meta.setDisplayName("§e§l" + targetPlayerName);
        List<String> lore = new ArrayList<>();
        lore.add("§7§m------------------------");
        lore.add("§7Tong so item: §f" + items.size());
        if (filterMaterial != null) {
            lore.add("§7Loc theo: §e" + filterMaterial);
            lore.add("§7Ket qua: §f" + filteredItems.size());
        }
        lore.add("§7Han dong cuoi: §f" + getLastActionDisplay());
        lore.add("§7§m------------------------");
        meta.setLore(lore);
        head.setItemMeta(meta);
        inventory.setItem(FILTER_SLOT, head);
    }

    private String getLastActionDisplay() {
        ItemData latest = filteredItems.stream()
            .max(Comparator.comparingLong(ItemData::getLastSeenAt))
            .orElse(null);
        if (latest == null || latest.getLastAction() == null) return "Khong co";
        String action = latest.getLastAction().toLowerCase();
        return plugin.getMessages().getRaw("action-" + action, action);
    }

    private void fillItems() {
        int itemsPerRow = COLUMNS;
        int start = (currentPage - 1) * itemsPerRow * 4;
        int end = Math.min(start + itemsPerRow * 4, filteredItems.size());

        int slot = PLAYER_START;
        for (int i = start; i < end; i++) {
            if (slot >= PLAYER_END) break;
            int row = (slot / 9);
            if (slot % 9 == 0) slot++;
            if (slot % 9 == 8) slot++;
            if (slot >= PLAYER_END) break;

            ItemData item = filteredItems.get(i);
            inventory.setItem(slot, makeItemIcon(item, i));
            slot++;
        }
    }

    private ItemStack makeItemIcon(ItemData item, int index) {
        Material mat = item.getMaterial() != null ? item.getMaterial() : Material.PAPER;
        ItemStack icon = new ItemStack(mat);

        String actionColor = getActionColor(item.getLastAction());
        String itemName = item.getDisplayName();

        ItemMeta meta = icon.getItemMeta();
        meta.setDisplayName(actionColor + itemName);

        List<String> lore = new ArrayList<>();
        lore.add("§7§m------------------------");
        lore.add("§7Ma: §e#" + item.getCode());
        lore.add("§7Loai: §f" + (item.getMaterial() != null ? formatMaterial(item.getMaterial().name()) : "Unknown"));
        List<String> itemLoreLines = item.getItemLoreLines();
        if (!itemLoreLines.isEmpty()) {
            lore.add("§7Lore:");
            for (String loreLine : itemLoreLines) {
                lore.add("§d  " + loreLine);
            }
        }
        String lastAction = item.getLastAction();
        String actionName = lastAction != null
            ? plugin.getMessages().getRaw("action-" + lastAction.toLowerCase(), lastAction)
            : "Khong co";
        lore.add("§7Han dong cuoi: §f" + actionName);
        lore.add("§7Vi tri: §f" + (item.getLastLocation() != null ? item.getLastLocation() : "Unknown"));
        lore.add("§7Phat hien: §f" + item.getDetectionCount() + "§7 lan");
        lore.add("§7§m------------------------");
        lore.add("§aClick: Xem lich su");
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(
            new org.bukkit.NamespacedKey(plugin, PREFIX + "item_idx"),
            PersistentDataType.INTEGER, index);
        meta.getPersistentDataContainer().set(
            new org.bukkit.NamespacedKey(plugin, PREFIX + "code"),
            PersistentDataType.STRING, item.getCode());

        icon.setItemMeta(meta);
        return icon;
    }

    private String getActionColor(String action) {
        if (action == null) return "§7";
        return switch (action.toUpperCase()) {
            case "PICKUP", "SPAWN" -> "§a";
            case "DROP", "VOID", "DEATH" -> "§c";
            case "CRAFT" -> "§e";
            case "USE" -> "§d";
            case "CONTAINER_TAKE" -> "§b";
            case "CONTAINER_PUT" -> "§3";
            case "CONTAINER_OPEN" -> "§9";
            default -> "§7";
        };
    }

    private void fillNavigation() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.setDisplayName(" ");
        filler.setItemMeta(fm);

        // Prev
        if (currentPage > 1) {
            inventory.setItem(PREV_SLOT, makeNavButton(Material.ARROW, "Trang truoc", "prev"));
        } else {
            inventory.setItem(PREV_SLOT, filler);
        }

        // Filter button (slot 4 already has player head, place elsewhere)
        inventory.setItem(FILTER_SLOT, makeFilterButton());

        // Close
        inventory.setItem(CLOSE_SLOT, makeCloseButton());

        // Next
        if (currentPage < totalPages) {
            inventory.setItem(NEXT_SLOT, makeNavButton(Material.ARROW, "Trang sau", "next"));
        } else {
            inventory.setItem(NEXT_SLOT, filler);
        }
    }

    private ItemStack makeFilterButton() {
        ItemStack btn = new ItemStack(Material.BOOK);
        ItemMeta meta = btn.getItemMeta();
        meta.setDisplayName("§e§lLoc Theo Loai Item");
        List<String> lore = new ArrayList<>();
        lore.add("§7§m------------------------");
        lore.add("§7Click de nhap loai item");
        lore.add("§7VD: fishing_rod, sword, bow...");
        lore.add("§7§m------------------------");
        if (filterMaterial != null) {
            lore.add("§7Hien dang loc: §e" + filterMaterial);
            lore.add("§7Click trai: Doi loc");
        }
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(
            new org.bukkit.NamespacedKey(plugin, PREFIX + "action"),
            PersistentDataType.STRING, "filter");

        btn.setItemMeta(meta);
        return btn;
    }

    private ItemStack makeNavButton(Material mat, String name, String action) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e" + name);
        List<String> lore = new ArrayList<>();
        lore.add("§7Click de " + name.toLowerCase());
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(
            new org.bukkit.NamespacedKey(plugin, PREFIX + "nav"),
            PersistentDataType.STRING, action);

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeCloseButton() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§cDong");
        List<String> lore = new ArrayList<>();
        lore.add("§7Click de dong GUI");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String formatMaterial(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toLowerCase().toCharArray()) {
            if (sb.length() == 0) {
                sb.append(Character.toUpperCase(c));
            } else if (c == '_') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public ItemGuard getPlugin() {
        return plugin;
    }

    public UUID getTargetPlayerUuid() {
        return targetPlayerUuid;
    }

    public String getTargetPlayerName() {
        return targetPlayerName;
    }

    public List<ItemData> getFilteredItems() {
        return filteredItems;
    }

    public List<ItemData> getAllItems() {
        return items;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    // --- MainBrowser: player list ---
    public static class MainBrowser implements InventoryHolder {
        public static final String TITLE = "§8§lItemGuard §7- Chon Nguoi Choi";
        private static final int SIZE = 54;
        private static final int CLOSE_SLOT = 49;
        private static final int PREV_SLOT = 45;
        private static final int NEXT_SLOT = 53;

        private final ItemGuard plugin;
        private final Player viewer;
        private Inventory inventory;
        private int currentPage = 1;
        private int totalPages = 1;
        private List<Player> onlinePlayers;
        static final String PREFIX = "ig_main_";

        public MainBrowser(ItemGuard plugin, Player viewer) {
            this.plugin = plugin;
            this.viewer = viewer;
            this.onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
            this.totalPages = Math.max(1, (int) Math.ceil((double) onlinePlayers.size() / 45.0));
        }

        public static void openMainBrowser(Player player) {
            new MainBrowser(ItemGuard.getInstance(), player).open();
        }

        public void open() {
            buildInventory();
            viewer.openInventory(inventory);
        }

        public void openPage(int page) {
            this.currentPage = Math.max(1, Math.min(page, totalPages));
            buildInventory();
            viewer.openInventory(inventory);
        }

        public ItemGuard getPlugin() {
            return plugin;
        }

        public int getCurrentPage() {
            return currentPage;
        }

        private void buildInventory() {
            String title = TITLE + " §7(Trang " + currentPage + "/" + totalPages + ")";
            inventory = Bukkit.createInventory(this, SIZE, title);

            fillBorder();
            fillPlayers();
            fillNavigation();
        }

        private void fillBorder() {
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fm = filler.getItemMeta();
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);

            for (int i = 0; i < 9; i++) {
                inventory.setItem(i, filler);
                inventory.setItem(i + SIZE - 9, filler);
            }
            for (int row = 1; row < 6; row++) {
                inventory.setItem(row * 9, filler);
                inventory.setItem(row * 9 + 8, filler);
            }
        }

        private void fillPlayers() {
            int start = (currentPage - 1) * 45;
            int end = Math.min(start + 45, onlinePlayers.size());

            int slot = 9;
            for (int i = start; i < end; i++) {
                while (slot % 9 == 0 || slot % 9 == 8) slot++;
                if (slot >= SIZE - 9) break;

                Player p = onlinePlayers.get(i);
                inventory.setItem(slot, makePlayerHead(p));
                slot++;
            }
        }

        private ItemStack makePlayerHead(Player p) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(p);
            meta.setDisplayName("§e§l" + p.getName());

            List<String> lore = new ArrayList<>();
            lore.add("§7§m------------------------");
            lore.add("§7Click de xem item cua nguoi nay");
            lore.add("§7");
            int itemCount = plugin.getDB().getItemsByPlayer(p.getUniqueId()).size();
            lore.add("§7Item dang theo doi: §f" + itemCount);
            lore.add("§7§m------------------------");
            meta.setLore(lore);

            meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, PREFIX + "player"),
                PersistentDataType.STRING, p.getUniqueId().toString());

            head.setItemMeta(meta);
            return head;
        }

        private void fillNavigation() {
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fm = filler.getItemMeta();
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);

            if (currentPage > 1) {
                inventory.setItem(PREV_SLOT, makeNavBtn(Material.ARROW, "Trang truoc", "prev"));
            } else {
                inventory.setItem(PREV_SLOT, filler);
            }

            inventory.setItem(CLOSE_SLOT, makeCloseBtn());

            if (currentPage < totalPages) {
                inventory.setItem(NEXT_SLOT, makeNavBtn(Material.ARROW, "Trang sau", "next"));
            } else {
                inventory.setItem(NEXT_SLOT, filler);
            }
        }

        private ItemStack makeNavBtn(Material mat, String name, String action) {
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName("§e" + name);
            List<String> lore = new ArrayList<>();
            lore.add("§7Click de " + name.toLowerCase());
            meta.setLore(lore);

            meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, PREFIX + "nav"),
                PersistentDataType.STRING, action);

            item.setItemMeta(meta);
            return item;
        }

        private ItemStack makeCloseBtn() {
            ItemStack item = new ItemStack(Material.BARRIER);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName("§cDong");
            List<String> lore = new ArrayList<>();
            lore.add("§7Click de dong GUI");
            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static boolean isOurGUI(Inventory inv) {
        if (inv == null) return false;
        Object h = inv.getHolder();
        return h instanceof PlayerBrowserGUI || h instanceof MainBrowser;
    }
}
