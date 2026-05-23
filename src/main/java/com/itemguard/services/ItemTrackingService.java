package com.itemguard.services;

import com.itemguard.ItemGuard;
import com.itemguard.data.DatabaseManager;
import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import com.itemguard.integrations.WorldGuardHook;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class ItemTrackingService {

    private final ItemGuard plugin;
    private final DatabaseManager db;
    private final Set<Material> forceTrack;
    private final Set<Material> bypassMaterials;

    private final Map<UUID, Set<String>> playerTrackedItems = new HashMap<>();

    private static final String KEY_CODE = "code";
    private static final String KEY_ITEM_UUID = "item_uuid";

    public ItemTrackingService(ItemGuard plugin) {
        this.plugin = plugin;
        this.db = plugin.getDB();
        this.forceTrack = plugin.getConfigs().getForceTrackMaterials();
        this.bypassMaterials = EnumSet.of(
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR
        );
    }

    public boolean shouldTrack(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (bypassMaterials.contains(item.getType())) return false;

        Material mat = item.getType();
        if (forceTrack.contains(mat)) return true;

        boolean isStackable = item.getMaxStackSize() > 1;
        if (isStackable) {
            return plugin.getConfigs().isTrackStackable();
        }
        return plugin.getConfigs().isTrackNonStackable();
    }

    public String getCodeFromItem(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(plugin.getNamespacedKey(KEY_CODE), PersistentDataType.STRING);
    }

    public UUID getItemUuidFromItem(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String uuidStr = pdc.get(plugin.getNamespacedKey(KEY_ITEM_UUID), PersistentDataType.STRING);
        if (uuidStr == null) return null;
        try {
            return UUID.fromString(uuidStr);
        } catch (Exception e) {
            return null;
        }
    }

    public ItemStack trackItem(ItemStack item, Player owner) {
        if (item == null || !shouldTrack(item)) return item;
        if (!hasCode(item)) {
            return tagItem(item, owner);
        }
        return item;
    }

    public ItemStack tagItem(ItemStack item, Player owner) {
        if (item == null) return null;

        String code = generateCode();
        UUID itemUuid = UUID.randomUUID();

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(plugin.getNamespacedKey(KEY_CODE), PersistentDataType.STRING, code);
        pdc.set(plugin.getNamespacedKey(KEY_ITEM_UUID), PersistentDataType.STRING, itemUuid.toString());

        applyVisualTag(meta, item, code);
        item.setItemMeta(meta);

        ItemData data = new ItemData(code, itemUuid);
        data.setMaterial(item.getType());
        data.setItemName(getDisplayName(item));
        if (owner != null) {
            data.setOwnerUuid(owner.getUniqueId());
            data.setOwnerName(owner.getName());
            data.setLastLocation(owner.getLocation());
        }
        data.setCurrentCount(1);

        db.saveItem(data);

        if (owner != null) {
            logHistory(code, itemUuid, "SPAWN", owner);
            trackPlayerItem(owner.getUniqueId(), code);
        }

        if (plugin.getConfigs().isDebug()) {
            plugin.getLogger().info("[DEBUG] Tagged item: " + code + " for " + owner);
        }

        return item;
    }

    private void applyVisualTag(ItemMeta meta, ItemStack item, String code) {
        FileConfiguration config = plugin.getConfig();

        if (config.getBoolean("uuid-tag.name-prefix", true)) {
            String format = config.getString("uuid-tag.name-prefix-format", "[#%CODE%]");
            String prefix = format.replace("%CODE%", code);
            String color = config.getString("uuid-tag.name-color", "&e");
            String coloredPrefix = ChatColor.translateAlternateColorCodes('&', color + prefix + " ");

            if (meta.hasDisplayName()) {
                String existing = meta.getDisplayName();
                meta.setDisplayName(coloredPrefix + existing);
            } else {
                String baseName = formatMaterialName(item.getType().name());
                meta.setDisplayName(coloredPrefix + baseName);
            }
        }

        if (config.getBoolean("uuid-tag.show-on-item", true)) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            String loreColor = config.getString("uuid-tag.lore-color", "&7&m");
            String tagLine = ChatColor.translateAlternateColorCodes('&', loreColor) + "ItemGuard: #" + code;

            int position = config.getInt("uuid-tag.lore-position", -1);
            if (position == -1 || position >= lore.size()) {
                lore.add(tagLine);
            } else {
                lore.add(position, tagLine);
            }
            meta.setLore(lore);
        }
    }

    public boolean hasCode(ItemStack item) {
        return getCodeFromItem(item) != null;
    }

    public boolean hasCodeOrUuid(ItemStack item) {
        return getCodeFromItem(item) != null || getItemUuidFromItem(item) != null;
    }

    public Optional<ItemData> getTrackedItem(String code) {
        return db.getItem(code);
    }

    public Optional<ItemData> getTrackedItemByUuid(UUID uuid) {
        return db.getItemByUuid(uuid);
    }

    public void onItemPickup(ItemStack item, Player player) {
        if (!plugin.getConfigs().isWorldEnabled(player.getWorld().getName())) return;
        if (plugin.getWorldGuardHook().isHooked() && !plugin.getWorldGuardHook().canTrack(player)) return;

        String code = getCodeFromItem(item);
        UUID itemUuid = getItemUuidFromItem(item);

        if (code != null && itemUuid != null) {
            db.updateItemLocation(code, player.getLocation(), player.getName(), player.getUniqueId());
            logHistory(code, itemUuid, "PICKUP", player);
            trackPlayerItem(player.getUniqueId(), code);
            checkDuplicate(itemUuid, player);
        } else if (shouldTrack(item)) {
            ItemStack tagged = trackItem(item, player);
            if (tagged != item) {
                player.getInventory().setItemInMainHand(tagged);
            }
        }
    }

    public void onItemDrop(ItemStack item, Player player) {
        String code = getCodeFromItem(item);
        UUID itemUuid = getItemUuidFromItem(item);
        if (code != null && itemUuid != null) {
            db.updateItemLocation(code, player.getLocation(), player.getName(), player.getUniqueId());
            logHistory(code, itemUuid, "DROP", player);
        }
    }

    public void onItemMoveInInventory(ItemStack item, Player player, String action) {
        String code = getCodeFromItem(item);
        UUID itemUuid = getItemUuidFromItem(item);
        if (code != null && itemUuid != null) {
            db.updateItemLocation(code, player.getLocation(), player.getName(), player.getUniqueId());
            logHistory(code, itemUuid, action, player);
            trackPlayerItem(player.getUniqueId(), code);
        }
    }

    public void onItemUse(ItemStack item, Player player) {
        String code = getCodeFromItem(item);
        UUID itemUuid = getItemUuidFromItem(item);
        if (code != null && itemUuid != null) {
            logHistory(code, itemUuid, "USE", player);
        }
    }

    public void onItemDeath(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && hasCode(item)) {
                String code = getCodeFromItem(item);
                UUID itemUuid = getItemUuidFromItem(item);
                if (code != null && itemUuid != null) {
                    logHistory(code, itemUuid, "DEATH", player);
                }
            }
        }
    }

    public void onItemCraft(ItemStack item, Player player) {
        if (shouldTrack(item)) {
            ItemStack tagged = tagItem(item, player);
            logHistory(getCodeFromItem(tagged), getItemUuidFromItem(tagged), "CRAFT", player);
        }
    }

    public void onContainerOpen(ItemStack item, Player player, String containerType) {
        String code = getCodeFromItem(item);
        UUID itemUuid = getItemUuidFromItem(item);
        if (code != null && itemUuid != null) {
            db.updateItemLocation(code, player.getLocation(), player.getName(), player.getUniqueId());
            logHistory(code, itemUuid, "CONTAINER_" + containerType, player);
        }
    }

    private void logHistory(String code, UUID itemUuid, String action, Player player) {
        if (code == null || itemUuid == null) return;
        ItemHistory history = new ItemHistory(
            code, itemUuid, action,
            player.getName(), player.getUniqueId(),
            formatLocation(player.getLocation())
        );
        db.logHistory(history);
    }

    private void checkDuplicate(UUID itemUuid, Player holder) {
        if (!plugin.getConfigs().isAntiDupeEnabled()) return;
        if (holder.hasPermission("itemguard.bypass")) return;

        Optional<ItemData> dataOpt = db.getItemByUuid(itemUuid);
        if (dataOpt.isEmpty()) return;

        ItemData data = dataOpt.get();
        long gracePeriod = data.getCreatedAt() + plugin.getConfigs().getGracePeriod();
        if (System.currentTimeMillis() < gracePeriod) return;

        int count = data.getCurrentCount();

        if (count > 1) {
            if (!db.canReportDuplicate(data.getCode())) return;

            db.incrementDuplicateCount();

            String itemName = data.getDisplayName();
            String code = data.getCode();
            String locations = data.getLastLocation();

            if (plugin.getConfigs().isNotifyStaff()) {
                notifyStaff(itemName, code, holder.getName(), locations);
            }

            if (plugin.getConfigs().isLogDuplicates()) {
                plugin.getLogger().warning("[DUPLICATE] Item " + code + " (" + itemName + ") held by " +
                    holder.getName() + " at " + locations + " - Count: " + count);
            }

            if (plugin.getDiscordWebhook().isEnabled()) {
                plugin.getDiscordWebhook().sendDuplicateAlert(itemName, code, holder.getName(), locations);
            }

            String action = plugin.getConfigs().getAntiDupeAction();
            switch (action) {
                case "REMOVE_NEWER" -> removeNewerItem(holder, itemUuid);
                case "REMOVE_OLDER" -> removeOlderItem(itemUuid);
                case "REMOVE_ALL" -> removeAllItems(itemUuid, holder);
            }
        }
    }

    private void notifyStaff(String itemName, String code, String holderName, String location) {
        String msg = ChatColor.translateAlternateColorCodes('&',
            "&c&l[CANH BAO DUPE!] &7Item &f" + itemName + " &7(Code: &e" + code + "&7) &7held by &f" + holderName + " &7at &f" + location);

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("itemguard.bypass")) {
                staff.sendMessage(msg);
            }
        }
    }

    private void removeNewerItem(Player holder, UUID itemUuid) {
        ItemStack[] contents = holder.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && itemUuid.equals(getItemUuidFromItem(item))) {
                holder.getInventory().setItem(i, null);
                holder.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c&l[CANH BAO!] &7Vat pham bi xoa vi trung lap!"));
                break;
            }
        }
    }

    private void removeOlderItem(UUID itemUuid) {
        List<Player> targets = new java.util.ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack[] contents = p.getInventory().getContents();
            for (ItemStack item : contents) {
                if (item != null && itemUuid.equals(getItemUuidFromItem(item))) {
                    targets.add(p);
                    break;
                }
            }
        }

        for (ItemSpawner spawner : Bukkit.getWorlds().stream()
                .flatMap(w -> w.getEntitiesByClass(org.bukkit.entity.Item.class).stream())
                .map(e -> new ItemSpawner(e))
                .filter(s -> itemUuid.equals(s.getItemUuid()))
                .toList()) {
            if (targets.isEmpty()) {
                spawner.remove();
                plugin.getLogger().info("[ItemGuard] Removed older duplicate item (ground) with UUID: " + itemUuid);
                return;
            }
            break;
        }
    }

    private static class ItemSpawner {
        private final org.bukkit.entity.Item entity;
        ItemSpawner(org.bukkit.entity.Item entity) { this.entity = entity; }
        UUID getItemUuid() {
            ItemStack stack = entity.getItemStack();
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return null;
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            String uuidStr = pdc.get(
                new NamespacedKey("itemguard", "item_uuid"),
                PersistentDataType.STRING);
            if (uuidStr == null) return null;
            try { return UUID.fromString(uuidStr); } catch (Exception e) { return null; }
        }
        void remove() { entity.remove(); }
    }

    private void removeAllItems(UUID itemUuid, Player holder) {
        ItemStack[] contents = holder.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && itemUuid.equals(getItemUuidFromItem(item))) {
                holder.getInventory().setItem(i, null);
            }
        }
    }

    private void trackPlayerItem(UUID playerUuid, String code) {
        playerTrackedItems.computeIfAbsent(playerUuid, k -> Collections.synchronizedSet(new HashSet<>()));
        playerTrackedItems.get(playerUuid).add(code);
    }

    public Set<String> getPlayerTrackedItems(UUID playerUuid) {
        Set<String> set = playerTrackedItems.get(playerUuid);
        return set != null ? set : Collections.emptySet();
    }

    public void scanPlayerInventory(Player player) {
        if (!plugin.getConfigs().isContainerScanEnabled()) return;

        int maxPerPlayer = plugin.getConfigs().getMaxTrackPerPlayer();
        Set<String> tracked = playerTrackedItems.computeIfAbsent(
            player.getUniqueId(), k -> Collections.synchronizedSet(new HashSet<>()));

        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) continue;
            if (count >= maxPerPlayer) break;

            if (shouldTrack(item) && !hasCode(item)) {
                ItemStack tagged = tagItem(item, player);
                if (tagged != null) {
                    player.getInventory().setItemInMainHand(tagged);
                }
                count++;
            } else if (hasCode(item)) {
                String code = getCodeFromItem(item);
                if (code != null) {
                    tracked.add(code);
                    UUID itemUuid = getItemUuidFromItem(item);
                    if (itemUuid != null) {
                        db.updateItemLocation(code, player.getLocation(), player.getName(), player.getUniqueId());
                    }
                }
            }
        }
    }

    public List<ItemStack> scanContainer(org.bukkit.block.Block block) {
        List<ItemStack> taggedItems = new ArrayList<>();
        if (block.getState() instanceof org.bukkit.block.Container container) {
            for (ItemStack item : container.getInventory().getContents()) {
                if (item != null && shouldTrack(item) && !hasCode(item)) {
                    ItemStack tagged = tagItem(item, null);
                    if (tagged != null) {
                        taggedItems.add(tagged);
                    }
                }
            }
        }
        return taggedItems;
    }

    public List<ItemStack> scanContainerInventory(org.bukkit.inventory.Inventory inventory) {
        List<ItemStack> taggedItems = new ArrayList<>();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && shouldTrack(item) && !hasCode(item)) {
                ItemStack tagged = tagItem(item, null);
                if (tagged != null) {
                    inventory.setItem(i, tagged);
                    taggedItems.add(tagged);
                }
            }
        }
        return taggedItems;
    }

    private String generateCode() {
        byte[] data;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            data = md.digest((System.nanoTime() + UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            data = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            int val = Math.abs(data[i] & 0xFF);
            sb.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".charAt(val % 36));
        }
        return sb.substring(0, 4) + "-" + sb.substring(4);
    }

    private String getDisplayName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            String name = item.getItemMeta().getDisplayName();
            return ChatColor.stripColor(name) != null ? name : name;
        }
        return formatMaterialName(item.getType().name());
    }

    private String formatMaterialName(String name) {
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

    private String formatLocation(org.bukkit.Location loc) {
        if (loc == null) return "Unknown";
        return String.format("%s (%d, %d, %d)",
            loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
