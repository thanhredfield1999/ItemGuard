package com.itemguard.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadPolicyTest {

    private final ReloadPolicy policy = new ReloadPolicy();
    private final RestartSensitiveSettings baseline = new RestartSensitiveSettings(
        "SQLITE",
        "itemguard.db",
        600,
        true,
        24,
        List.of("DIAMOND_SWORD"),
        true,
        false,
        "",
        "ItemGuard Alerts",
        "",
        20,
        false
    );

    @Test
    void unchangedRestartSettingsAllowReloadSafeConfig() {
        ReloadDecision decision = policy.evaluate(baseline, baseline);

        assertEquals(ReloadStatus.APPLY, decision.status());
        assertTrue(decision.changedRestartKeys().isEmpty());
    }

    @Test
    void databaseTaskMaterialAndHookChangesRequireRestart() {
        RestartSensitiveSettings candidate = new RestartSensitiveSettings(
            "MYSQL",
            "other.db",
            20,
            false,
            12,
            List.of("NETHERITE_SWORD"),
            false,
            true,
            "https://example.invalid",
            "Alerts",
            "avatar",
            30,
            true
        );

        ReloadDecision decision = policy.evaluate(baseline, candidate);

        assertEquals(ReloadStatus.RESTART_REQUIRED, decision.status());
        assertEquals(List.of(
            "database.type",
            "database.sqlite.file-name",
            "performance.inventory-scan-interval",
            "performance.auto-cleanup.enabled",
            "performance.auto-cleanup.interval-hours",
            "tracking.force-track-materials",
            "worlds.worldguard-support",
            "discord.enabled",
            "discord.webhook-url",
            "discord.username",
            "discord.avatar-url",
            "reclaim.history-days",
            "reclaim.issuance-enabled"
        ), decision.changedRestartKeys());
    }
}
