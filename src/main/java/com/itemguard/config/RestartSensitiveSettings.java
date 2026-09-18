package com.itemguard.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public record RestartSensitiveSettings(
    String databaseType,
    String sqliteFileName,
    int inventoryScanInterval,
    boolean autoCleanupEnabled,
    int cleanupIntervalHours,
    List<String> forceTrackMaterials,
    boolean worldGuardEnabled,
    boolean discordEnabled,
    String discordWebhookUrl,
    String discordUsername,
    String discordAvatarUrl,
    int reclaimHistoryDays,
    String externalAbsenceMode,
    boolean reclaimIssuanceEnabled
) {
    public RestartSensitiveSettings {
        forceTrackMaterials = forceTrackMaterials.stream().sorted().toList();
    }

    public static RestartSensitiveSettings from(FileConfiguration config) {
        return new RestartSensitiveSettings(
            config.getString("database.type", "SQLITE"),
            config.getString("database.sqlite.file-name", "itemguard.db"),
            config.getInt("performance.inventory-scan-interval", 600),
            config.getBoolean("performance.auto-cleanup.enabled", false),
            config.getInt("performance.auto-cleanup.interval-hours", 24),
            config.getStringList("tracking.force-track-materials"),
            config.getBoolean("worlds.worldguard-support", false),
            config.getBoolean("discord.enabled", false),
            config.getString("discord.webhook-url", ""),
            config.getString("discord.username", "ItemGuard Alerts"),
            config.getString("discord.avatar-url", ""),
            config.getInt("reclaim.history-days", 20),
            config.getString("reclaim.external-absence-mode", "STRICT"),
            config.getBoolean("reclaim.issuance-enabled", false)
        );
    }
}
