package com.itemguard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DefaultConfigContractTest {

    @Test
    void reclaimHistoryDefaultsToTwentyDays() {
        try (InputStream input = getClass().getResourceAsStream("/config.yml")) {
            if (input == null) {
                throw new IllegalStateException("Missing config.yml test resource");
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new InputStreamReader(input, StandardCharsets.UTF_8)
            );

            assertEquals(20, config.getInt("reclaim.history-days"));
            assertFalse(config.getBoolean("worlds.worldguard-support"));
            assertFalse(config.getBoolean("tracking.track-stackable"));
            // Multi-server keys ship with a blank name (nothing configured) and a 30 minute window.
            // A blank name must stay blank rather than `null`, or the identity resolver would see
            // "not configured" as a name.
            assertEquals("", config.getString("multi-server.server-id"));
            assertEquals(30, config.getInt("multi-server.cross-server-window-minutes"));
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
