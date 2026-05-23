package com.itemguard.data;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.UUID;

public class ItemData {

    private final String code;
    private final UUID itemUuid;
    private UUID ownerUuid;
    private String ownerName;
    private Material material;
    private String itemName;
    private long createdAt;
    private long lastSeenAt;
    private int currentCount;
    private String lastLocation;

    public ItemData(String code, UUID itemUuid) {
        this.code = code;
        this.itemUuid = itemUuid;
        this.createdAt = Instant.now().toEpochMilli();
        this.lastSeenAt = createdAt;
        this.currentCount = 1;
    }

    public String getCode() {
        return code;
    }

    public UUID getItemUuid() {
        return itemUuid;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public Material getMaterial() {
        return material;
    }

    public void setMaterial(Material material) {
        this.material = material;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(long lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public int getCurrentCount() {
        return currentCount;
    }

    public void setCurrentCount(int currentCount) {
        this.currentCount = currentCount;
    }

    public void incrementCount() {
        this.currentCount++;
    }

    public String getLastLocation() {
        return lastLocation;
    }

    public void setLastLocation(String lastLocation) {
        this.lastLocation = lastLocation;
    }

    public void setLastLocation(Location loc) {
        if (loc == null) {
            this.lastLocation = "Unknown";
        } else {
            this.lastLocation = String.format("%s (%d, %d, %d)",
                loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }
    }

    public String getDisplayName() {
        if (itemName != null && !itemName.isEmpty()) {
            return itemName;
        }
        if (material != null) {
            return formatMaterialName(material.name());
        }
        return "Unknown Item";
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

    public ItemStack toItemStack() {
        if (material == null) {
            material = Material.DIAMOND_SWORD;
        }
        return new ItemStack(material);
    }

    @Override
    public String toString() {
        return "ItemData{" +
            "code='" + code + '\'' +
            ", itemUuid=" + itemUuid +
            ", ownerName='" + ownerName + '\'' +
            ", material=" + material +
            ", currentCount=" + currentCount +
            '}';
    }
}
