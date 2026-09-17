package com.itemguard.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ReloadPolicy {

    public ReloadDecision evaluate(
        RestartSensitiveSettings current,
        RestartSensitiveSettings candidate
    ) {
        List<String> changed = new ArrayList<>();
        addIfChanged(changed, "database.type", current.databaseType(), candidate.databaseType());
        addIfChanged(changed, "database.sqlite.file-name", current.sqliteFileName(), candidate.sqliteFileName());
        addIfChanged(changed, "performance.inventory-scan-interval", current.inventoryScanInterval(), candidate.inventoryScanInterval());
        addIfChanged(changed, "performance.auto-cleanup.interval-hours", current.cleanupIntervalHours(), candidate.cleanupIntervalHours());
        addIfChanged(changed, "tracking.force-track-materials", current.forceTrackMaterials(), candidate.forceTrackMaterials());
        addIfChanged(changed, "worlds.worldguard-support", current.worldGuardEnabled(), candidate.worldGuardEnabled());
        addIfChanged(changed, "discord.enabled", current.discordEnabled(), candidate.discordEnabled());
        addIfChanged(changed, "discord.webhook-url", current.discordWebhookUrl(), candidate.discordWebhookUrl());
        addIfChanged(changed, "discord.username", current.discordUsername(), candidate.discordUsername());
        addIfChanged(changed, "discord.avatar-url", current.discordAvatarUrl(), candidate.discordAvatarUrl());
        addIfChanged(changed, "reclaim.history-days", current.reclaimHistoryDays(), candidate.reclaimHistoryDays());
        addIfChanged(changed, "reclaim.issuance-enabled", current.reclaimIssuanceEnabled(), candidate.reclaimIssuanceEnabled());

        ReloadStatus status = changed.isEmpty()
            ? ReloadStatus.APPLY
            : ReloadStatus.RESTART_REQUIRED;
        return new ReloadDecision(status, changed);
    }

    private void addIfChanged(List<String> changed, String key, Object current, Object candidate) {
        if (!Objects.equals(current, candidate)) {
            changed.add(key);
        }
    }
}
