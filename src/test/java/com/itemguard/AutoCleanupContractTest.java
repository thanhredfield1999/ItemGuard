package com.itemguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itemguard.config.RestartSensitiveSettings;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * {@code performance.auto-cleanup.enabled} was read by nothing: only {@code interval-hours} decided
 * whether the scheduled task ran, so an operator who set {@code enabled: false} after reading the
 * file still had history deleted on the next cycle — the one setting whose whole purpose is to say
 * "do not touch my audit trail".
 *
 * <p>The shipped default is now {@code false} as well. This plugin exists to keep evidence; deleting
 * rows older than {@code keep-days} has to be asked for, not inherited.
 */
class AutoCleanupContractTest {

    private static FileConfiguration shipped() {
        try (InputStream input = AutoCleanupContractTest.class.getResourceAsStream("/config.yml")) {
            if (input == null) {
                throw new IllegalStateException("Missing config.yml test resource");
            }
            return YamlConfiguration.loadConfiguration(
                new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    void theShippedDefaultDoesNotDeleteHistory() {
        FileConfiguration config = shipped();

        assertFalse(config.getBoolean("performance.auto-cleanup.enabled"),
            "a fresh install must not start deleting history");
        assertTrue(config.getInt("performance.auto-cleanup.keep-days") > 0,
            "the window still ships with a value so enabling it is one edit");
    }

    @Test
    void theSwitchIsPartOfWhatAReloadReportsAsRestartSensitive() {
        FileConfiguration off = shipped();
        off.set("performance.auto-cleanup.enabled", false);
        FileConfiguration on = shipped();
        on.set("performance.auto-cleanup.enabled", true);

        assertNotEquals(
            RestartSensitiveSettings.from(off),
            RestartSensitiveSettings.from(on),
            "flipping the switch has to be visible to the reload policy, or /ig reload would "
                + "silently keep deleting after an operator turned it off");
    }

    @Test
    void theManagerReadsTheSwitchAndTheSchedulerHonoursIt() throws Exception {
        String manager = Files.readString(
            Path.of("src/main/java/com/itemguard/ConfigManager.java"));
        String scheduler = Files.readString(
            Path.of("src/main/java/com/itemguard/ItemGuard.java"));

        assertTrue(manager.contains("performance.auto-cleanup.enabled"),
            "ConfigManager must read the enabled key");
        assertTrue(manager.contains("isAutoCleanupEnabled"),
            "and expose it as a named decision");
        assertTrue(manager.contains("isAutoCleanupEnabled()"),
            "getCleanupIntervalHours must consult the switch");
        assertEquals(1, countOccurrences(scheduler, "getCleanupIntervalHours()"),
            "scheduling still runs off the interval, which is now 0 when the switch is off");
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }
}
