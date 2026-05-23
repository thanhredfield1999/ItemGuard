package com.itemguard.data;

import java.time.Instant;
import java.util.UUID;

public class ItemHistory {

    private long id;
    private String code;
    private UUID itemUuid;
    private String action;
    private String playerName;
    private UUID playerUuid;
    private String location;
    private String world;
    private int x, y, z;
    private long timestamp;
    private String additionalData;

    public ItemHistory() {}

    public ItemHistory(String code, UUID itemUuid, String action, String playerName, UUID playerUuid, String location) {
        this.code = code;
        this.itemUuid = itemUuid;
        this.action = action;
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.location = location;
        this.timestamp = Instant.now().toEpochMilli();
        parseLocation(location);
    }

    private void parseLocation(String loc) {
        if (loc == null || loc.isEmpty()) {
            this.world = "unknown";
            this.x = 0;
            this.y = 0;
            this.z = 0;
            return;
        }
        try {
            String[] parts = loc.split(" ");
            if (parts.length >= 4) {
                this.world = parts[0].replace("(", "");
                String coords = parts[1].replace("(", "").replace(",", "");
                this.x = Integer.parseInt(coords);
                String[] yz = parts[2].replace(",", "").split(",");
                if (yz.length >= 2) {
                    this.y = Integer.parseInt(yz[0]);
                    this.z = Integer.parseInt(yz[1].replace(")", ""));
                }
            }
        } catch (Exception ignored) {
            this.world = "unknown";
        }
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public UUID getItemUuid() {
        return itemUuid;
    }

    public void setItemUuid(UUID itemUuid) {
        this.itemUuid = itemUuid;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getWorld() {
        return world;
    }

    public void setWorld(String world) {
        this.world = world;
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    public int getZ() {
        return z;
    }

    public void setZ(int z) {
        this.z = z;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getAdditionalData() {
        return additionalData;
    }

    public void setAdditionalData(String additionalData) {
        this.additionalData = additionalData;
    }

    public String getFormattedTimestamp() {
        return formatTimestamp(timestamp);
    }

    private String formatTimestamp(long ts) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(ts);
        java.time.LocalDateTime ldt = java.time.LocalDateTime.ofInstant(instant,
            java.time.ZoneId.systemDefault());
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss");
        return ldt.format(fmt);
    }

    public String getRelativeTime() {
        long now = Instant.now().toEpochMilli();
        long diff = now - timestamp;

        if (diff < 60000) {
            return (diff / 1000) + "s ago";
        } else if (diff < 3600000) {
            return (diff / 60000) + "m ago";
        } else if (diff < 86400000) {
            return (diff / 3600000) + "h ago";
        } else {
            return (diff / 86400000) + "d ago";
        }
    }

    @Override
    public String toString() {
        return "ItemHistory{" +
            "id=" + id +
            ", code='" + code + '\'' +
            ", action='" + action + '\'' +
            ", playerName='" + playerName + '\'' +
            ", location='" + location + '\'' +
            ", timestamp=" + timestamp +
            '}';
    }
}
