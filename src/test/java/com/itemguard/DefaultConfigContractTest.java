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
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
