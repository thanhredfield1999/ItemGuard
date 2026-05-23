package com.itemguard;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class ConfigManager {

    private final ItemGuard plugin;
    private FileConfiguration config;

    public ConfigManager(ItemGuard plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        this.config = plugin.getConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        load();
    }

    // ---------- GENERAL ----------
    public boolean isEnabled() {
        return config.getBoolean("general.enabled", true);
    }

    public boolean isDebug() {
        return config.getBoolean("general.debug", false);
    }

    public String getLanguage() {
        return config.getString("general.language", "vi");
    }

    // ---------- TRACKING ----------
    public boolean isTrackingEnabled() {
        return config.getBoolean("tracking.enabled", true);
    }

    public boolean isTrackNonStackable() {
        return config.getBoolean("tracking.track-non-stackable", true);
    }

    public boolean isTrackStackable() {
        return config.getBoolean("tracking.track-stackable", false);
    }

    public Set<Material> getForceTrackMaterials() {
        Set<Material> materials = new HashSet<>();
        List<String> list = config.getStringList("tracking.force-track-materials");
        for (String name : list) {
            try {
                materials.add(Material.valueOf(name));
            } catch (IllegalArgumentException ignored) {}
        }
        return materials;
    }

    public boolean shouldTrack(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (!isTrackingEnabled()) return false;

        boolean isStackable = item.getMaxStackSize() > 1;

        if (isStackable && !isTrackStackable()) {
            return getForceTrackMaterials().contains(item.getType());
        }

        if (!isStackable && !isTrackNonStackable()) {
            return getForceTrackMaterials().contains(item.getType());
        }

        if (!isStackable) return true;
        return getForceTrackMaterials().contains(item.getType());
    }

    // ---------- UUID TAG ----------
    public boolean isShowUuidTag() {
        return config.getBoolean("uuid-tag.show-on-item", false);
    }

    public int getLorePosition() {
        return config.getInt("uuid-tag.lore-position", -1);
    }

    // ---------- ANTI-DUPE ----------
    public boolean isAntiDupeEnabled() {
        return config.getBoolean("anti-dupe.enabled", true);
    }

    public String getAntiDupeAction() {
        return config.getString("anti-dupe.action", "NOTIFY");
    }

    public boolean isNotifyStaff() {
        return config.getBoolean("anti-dupe.notify-staff", true);
    }

    public boolean isNotifyPlayer() {
        return config.getBoolean("anti-dupe.notify-player", false);
    }

    public long getDetectionCooldown() {
        return config.getLong("anti-dupe.detection-cooldown-ms", 5000);
    }

    public int getMaxHistoryPerItem() {
        return config.getInt("anti-dupe.max-history-per-item", 1000);
    }

    public long getGracePeriod() {
        return config.getLong("anti-dupe.grace-period-ms", 3000);
    }

    // ---------- DATABASE ----------
    public String getDatabaseType() {
        return config.getString("database.type", "SQLITE");
    }

    public String getSqliteFileName() {
        return config.getString("database.sqlite.file-name", "itemguard.db");
    }

    public String getMySQLHost() {
        return config.getString("database.mysql.host", "localhost");
    }

    public int getMySQLPort() {
        return config.getInt("database.mysql.port", 3306);
    }

    public String getMySQLDatabase() {
        return config.getString("database.mysql.database", "itemguard");
    }

    public String getMySQLUsername() {
        return config.getString("database.mysql.username", "root");
    }

    public String getMySQLPassword() {
        return config.getString("database.mysql.password", "");
    }

    public boolean getMySQLSSL() {
        return config.getBoolean("database.mysql.use-ssl", false);
    }

    public String getPostgresHost() {
        return config.getString("database.postgres.host", "localhost");
    }

    public int getPostgresPort() {
        return config.getInt("database.postgres.port", 5432);
    }

    public String getPostgresDatabase() {
        return config.getString("database.postgres.database", "itemguard");
    }

    public String getPostgresUsername() {
        return config.getString("database.postgres.username", "postgres");
    }

    public String getPostgresPassword() {
        return config.getString("database.postgres.password", "");
    }

    public boolean getPostgresSSL() {
        return config.getBoolean("database.postgres.use-ssl", false);
    }

    public int getPoolMaxSize() {
        return config.getInt("database.pool.maximum-pool-size", 10);
    }

    public int getPoolMinIdle() {
        return config.getInt("database.pool.minimum-idle", 2);
    }

    public long getPoolConnectionTimeout() {
        return config.getLong("database.pool.connection-timeout-ms", 30000);
    }

    // ---------- GUI ----------
    public String getGuiTitle() {
        return colorize(config.getString("gui.title", "&8&lItemGuard &7- &fLich Su Item"));
    }

    public int getGuiHistoryRows() {
        return config.getInt("gui.history-rows", 5);
    }

    public int getEntriesPerPage() {
        return config.getInt("gui.entries-per-page", 45);
    }

    public boolean isShowItemInHistory() {
        return config.getBoolean("gui.item-display.show-item", true);
    }

    public boolean isShowTimestamp() {
        return config.getBoolean("gui.item-display.show-timestamp", true);
    }

    public boolean isShowLocation() {
        return config.getBoolean("gui.item-display.show-location", true);
    }

    public boolean isShowAction() {
        return config.getBoolean("gui.item-display.show-action", true);
    }

    public boolean isShowPlayer() {
        return config.getBoolean("gui.item-display.show-player", true);
    }

    public String getGuiFillerMaterial() {
        return config.getString("gui.colors.filler-item", "GRAY_STAINED_GLASS_PANE");
    }

    public String getGuiNextPageMaterial() {
        return config.getString("gui.colors.next-page", "ARROW");
    }

    public String getGuiPrevPageMaterial() {
        return config.getString("gui.colors.prev-page", "ARROW");
    }

    public String getGuiCloseMaterial() {
        return config.getString("gui.colors.close-button", "BARRIER");
    }

    // ---------- LOGGING ----------
    public boolean isLogToConsole() {
        return config.getBoolean("logging.log-to-console", true);
    }

    public String getConsoleLogLevel() {
        return config.getString("logging.console-level", "INFO");
    }

    public boolean isLogDuplicates() {
        return config.getBoolean("logging.log-duplicates", true);
    }

    public boolean isFileLoggingEnabled() {
        return config.getBoolean("logging.file.enabled", true);
    }

    public String getLogFileName() {
        return config.getString("logging.file.file-name", "itemguard.log");
    }

    // ---------- PERFORMANCE ----------
    public boolean isAsyncDatabase() {
        return config.getBoolean("performance.async-database", true);
    }

    public int getBatchSize() {
        return config.getInt("performance.batch-size", 100);
    }

    public int getCleanupIntervalHours() {
        return config.getInt("performance.auto-cleanup.interval-hours", 24);
    }

    public int getCleanupKeepDays() {
        return config.getInt("performance.auto-cleanup.keep-days", 30);
    }

    public boolean isContainerScanEnabled() {
        return config.getBoolean("performance.container-scan-enabled", true);
    }

    public int getInventoryScanInterval() {
        return config.getInt("performance.inventory-scan-interval", 600);
    }

    public int getMaxTrackPerPlayer() {
        return config.getInt("performance.max-track-per-player", 500);
    }

    // ---------- WORLD MANAGEMENT ----------
    public boolean isWorldEnabled(String worldName) {
        if (worldName == null) return true;
        List<String> disabled = config.getStringList("worlds.disabled-worlds");
        return !disabled.contains(worldName);
    }

    public boolean isWorldGuardEnabled() {
        return config.getBoolean("worlds.worldguard-support", true);
    }

    // ---------- DISCORD ----------
    public boolean isDiscordWebhookEnabled() {
        return config.getBoolean("discord.enabled", false);
    }

    public String getDiscordWebhookUrl() {
        return config.getString("discord.webhook-url", "");
    }

    public String getDiscordWebhookUsername() {
        return config.getString("discord.username", "ItemGuard Alerts");
    }

    public String getDiscordWebhookAvatar() {
        return config.getString("discord.avatar-url", "");
    }

    // ---------- HELPERS ----------
    private String colorize(String msg) {
        if (msg == null) return "";
        return msg.replace("&", "§");
    }
}
