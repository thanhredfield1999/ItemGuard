package com.itemguard;

import com.itemguard.config.RestartSensitiveSettings;
import com.itemguard.config.AntiDupeSettings;
import com.itemguard.tracking.ItemIdentityEligibilityPolicy;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class ConfigManager {

    private final ItemGuard plugin;
    private final ItemIdentityEligibilityPolicy identityEligibilityPolicy =
        new ItemIdentityEligibilityPolicy();
    private FileConfiguration config;

    public ConfigManager(ItemGuard plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        this.config = plugin.getConfig();
    }

    public void reload() {
        load();
    }

    public RestartSensitiveSettings getRestartSensitiveSettings() {
        return RestartSensitiveSettings.from(config);
    }

    // ---------- GENERAL ----------
    public boolean isEnabled() {
        return config.getBoolean("general.enabled", true);
    }

    public boolean isDebug() {
        return config.getBoolean("general.debug", false);
    }

    public String getLanguage() {
        return new com.itemguard.config.MessageLanguagePolicy().resolve(
            config.getString("general.language"),
            plugin.isLiteEdition()
        );
    }

    // ---------- TRACKING ----------
    public boolean isTrackingEnabled() {
        return config.getBoolean("tracking.enabled", true);
    }

    /**
     * C1 (review 2026-09-17): whether a craft whose result would need a brand-new identity is
     * cancelled. Default true — the behaviour this plugin has always had, and the one its own
     * README discloses. It is a switch because cancelling every crafted tool, weapon and armour
     * piece is a large thing to do to a server, and the Spigot page did not say so.
     */
    public boolean cancelsUntrackedCraftOutput() {
        return config.getBoolean("tracking.cancel-untracked-craft-output", true);
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
        return identityEligibilityPolicy.shouldTrack(
            item.getMaxStackSize(),
            item.getAmount(),
            isTrackingEnabled(),
            isTrackNonStackable(),
            isTrackStackable(),
            getForceTrackMaterials().contains(item.getType())
        );
    }

    // ---------- UUID TAG ----------
    public boolean isShowUuidTag() {
        return config.getBoolean("uuid-tag.show-on-item", false);
    }

    public int getLorePosition() {
        return config.getInt("uuid-tag.lore-position", -1);
    }

    // ---------- ANTI-DUPE ----------
    private AntiDupeSettings antiDupeSettings() {
        return AntiDupeSettings.from(config);
    }

    public boolean isAntiDupeEnabled() {
        return antiDupeSettings().enabled();
    }

    public String getAntiDupeAction() {
        return plugin.isLiteEdition() ? "NOTIFY" : antiDupeSettings().action();
    }

    public boolean isNotifyStaff() {
        return antiDupeSettings().notifyStaff();
    }

    public boolean isNotifyPlayer() {
        return antiDupeSettings().notifyPlayer();
    }

    public long getDetectionCooldown() {
        return antiDupeSettings().detectionCooldown();
    }

    public int getMaxHistoryPerItem() {
        return antiDupeSettings().maxHistoryPerItem();
    }

    public long getGracePeriod() {
        return antiDupeSettings().gracePeriod();
    }

    public boolean isSweepEnabled() {
        return antiDupeSettings().sweepEnabled();
    }

    public int getSweepChunksPerTick() {
        return antiDupeSettings().sweepChunksPerTick();
    }

    // ---------- DATABASE ----------
    public String getDatabaseType() {
        return config.getString("database.type", "SQLITE");
    }

    public String getSqliteFileName() {
        return config.getString("database.sqlite.file-name", "itemguard.db");
    }

    /** Premium migration target; credentials are never logged by ItemGuard. */
    public String getMySqlUrl() {
        return config.getString("database.mysql.url", "");
    }

    public String getMySqlUser() {
        return config.getString("database.mysql.user", "");
    }

    public String getMySqlPassword() {
        return config.getString("database.mysql.password", "");
    }

    /**
     * Size at which to warn that the database is growing, in bytes; 0 disables the warning.
     *
     * <p>Only relevant in LITE, where automatic history deletion is off and the file therefore
     * only ever grows. 500 MB is a deliberately unalarming default: large enough that a normal
     * server never sees the message, small enough to arrive long before a full disk.
     */
    public long getDatabaseWarnBytes() {
        long megabytes = config.getLong("database.warn-size-mb", 500L);
        return megabytes <= 0 ? 0L : megabytes * 1024L * 1024L;
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

    // ---------- MULTI-SERVER (MySQL backend only) ----------

    /**
     * This server's name as it appears in a cross-server finding; blank means "not configured",
     * which {@code ServerIdentityPolicy} resolves from a stored or generated name.
     */
    public String getServerId() {
        return config.getString("multi-server.server-id", "");
    }

    /**
     * How far back a sighting still counts for the cross-server rule, in milliseconds.
     *
     * <p>Returned as a duration rather than the configured minutes so that the refusal for a
     * non-positive window lives in one place — {@code CrossServerFindingPolicy} — instead of
     * being re-derived by each caller. A window of 0 reports nothing while looking configured,
     * which is the exact failure a settings-reading codebase keeps making here.
     */
    public long getCrossServerWindowMillis() {
        return config.getLong("multi-server.cross-server-window-minutes", 30L) * 60_000L;
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

    public int getBatchSize() {
        return config.getInt("performance.batch-size", 100);
    }

    public boolean isAutoCleanupEnabled() {
        // The switch decides *whether*, the interval only says how often. Nothing read this key
        // before: `enabled: false` still deleted history on the next cycle, which is the one setting
        // whose whole purpose is to say "do not touch my audit trail".
        return !plugin.isLiteEdition()
            && config.getBoolean("performance.auto-cleanup.enabled", false);
    }

    public int getCleanupIntervalHours() {
        // LITE is an investigation build: automatic history deletion stays off regardless of config.
        if (!isAutoCleanupEnabled()) {
            return 0;
        }
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

    public boolean isReclaimIssuanceEnabled() {
        // The gate gates *issuing*, and LITE has no reclaim command at all, so it can never be on
        // there regardless of what a copied config says.
        return !plugin.isLiteEdition() && config.getBoolean("reclaim.issuance-enabled", false);
    }

    public int getReclaimHistoryDays() {
        return Math.max(1, config.getInt("reclaim.history-days", 20));
    }

    // ---------- WORLD MANAGEMENT ----------
    public boolean isWorldEnabled(String worldName) {
        if (worldName == null) return true;
        List<String> disabled = config.getStringList("worlds.disabled-worlds");
        return !disabled.contains(worldName);
    }

    public boolean isWorldGuardEnabled() {
        return !plugin.isLiteEdition() && config.getBoolean("worlds.worldguard-support", false);
    }

    // ---------- DISCORD ----------
    public boolean isDiscordWebhookEnabled() {
        return !plugin.isLiteEdition() && config.getBoolean("discord.enabled", false);
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
