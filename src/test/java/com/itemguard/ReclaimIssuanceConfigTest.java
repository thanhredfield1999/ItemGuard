package com.itemguard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The reclaim gate: who can turn it on, and what a server that never touched the config gets.
 *
 * <p>The key existed before this test and was read by nothing — the same class of trap as
 * {@code performance.auto-cleanup.enabled}, which is why both are pinned here. Two rules: the shipped
 * default is off, and LITE is off whatever a copied config says, because LITE has no reclaim command
 * to be careful about.
 */
class ReclaimIssuanceConfigTest {

    private static YamlConfiguration shippedConfig() throws Exception {
        try (var stream = ReclaimIssuanceConfigTest.class.getResourceAsStream("/config.yml")) {
            assertTrue(stream != null, "the shipped config.yml must be on the classpath");
            return YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                stream, java.nio.charset.StandardCharsets.UTF_8
            ));
        }
    }

    @Test
    void theKeyExistsAndShipsOff() throws Exception {
        YamlConfiguration config = shippedConfig();

        assertTrue(config.contains("reclaim.issuance-enabled"),
            "the gate must be a real key an operator can find, not a code constant");
        assertFalse(config.getBoolean("reclaim.issuance-enabled"),
            "issuance ships off: it hands out items, and the release gate has to be run first");
    }

    @Test
    void theCommentExplainsWhatTheGateNowBlocks() throws Exception {
        String comment = Files.readString(Path.of("src/main/resources/config.yml"));
        int key = comment.indexOf("issuance-enabled:");
        int start = comment.lastIndexOf("# ---------- RECLAIM", key);
        String block = comment.substring(start, key);

        assertTrue(block.contains("issue"), block);
        assertFalse(block.contains("not implemented"),
            "the issuance transaction now exists; a comment claiming otherwise would be stale");
    }

    @Test
    void fullObeysTheConfigAndLiteNeverIssues() {
        ItemGuard full = mock(ItemGuard.class);
        when(full.isLiteEdition()).thenReturn(false);
        YamlConfiguration on = new YamlConfiguration();
        on.set("reclaim.issuance-enabled", true);
        when(full.getConfig()).thenReturn(on);
        assertTrue(new ConfigManager(full).isReclaimIssuanceEnabled(),
            "FULL with the gate on must be able to issue");

        ItemGuard lite = mock(ItemGuard.class);
        when(lite.isLiteEdition()).thenReturn(true);
        when(lite.getConfig()).thenReturn(on);
        assertFalse(new ConfigManager(lite).isReclaimIssuanceEnabled(),
            "LITE cannot issue even with the key on: it has no reclaim command and no snapshot path");

        ItemGuard off = mock(ItemGuard.class);
        when(off.isLiteEdition()).thenReturn(false);
        YamlConfiguration disabled = new YamlConfiguration();
        disabled.set("reclaim.issuance-enabled", false);
        when(off.getConfig()).thenReturn(disabled);
        assertFalse(new ConfigManager(off).isReclaimIssuanceEnabled());
    }

    @Test
    void anAbsentKeyIsOff() {
        ItemGuard full = mock(ItemGuard.class);
        when(full.isLiteEdition()).thenReturn(false);
        when(full.getConfig()).thenReturn(new YamlConfiguration());

        assertFalse(new ConfigManager(full).isReclaimIssuanceEnabled(),
            "a config file that predates the key must not enable issuance by omission");
    }
}
