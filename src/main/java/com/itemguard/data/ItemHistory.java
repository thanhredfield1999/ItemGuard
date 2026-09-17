package com.itemguard.data;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ItemHistory {

    private static final Pattern LOCATION_PATTERN = Pattern.compile(
        "^(.+) \\((-?\\d+), (-?\\d+), (-?\\d+)\\)$");

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
        this.world = "unknown";
        this.x = 0;
        this.y = 0;
        this.z = 0;

        if (loc == null || loc.isEmpty()) {
            return;
        }

        try {
            Matcher matcher = LOCATION_PATTERN.matcher(loc);
            if (matcher.matches()) {
                this.world = matcher.group(1);
                this.x = Integer.parseInt(matcher.group(2));
                this.y = Integer.parseInt(matcher.group(3));
                this.z = Integer.parseInt(matcher.group(4));
            }
        } catch (NumberFormatException ignored) {
            this.world = "unknown";
            this.x = 0;
            this.y = 0;
            this.z = 0;
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
