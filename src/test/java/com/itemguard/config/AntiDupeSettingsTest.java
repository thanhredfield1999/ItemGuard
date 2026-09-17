package com.itemguard.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H4 (review 2026-09-16): the anti-repeat window shipped as 5 seconds against a 30-second scan
 * cycle, which means it could never suppress a repeat — the same identity is re-detected one cycle
 * later, always outside the window. Staff were re-alerted for as long as the duplicate existed and
 * a row was written every cycle. The setting looked like a throttle and was inert.
 *
 * <p>M1 (review 2026-09-17): the first fix floored the window at exactly one cycle, which is still
 * inert for the same reason — the suppression test is strict (`previous.created_at > now - cooldown`)
 * and two audits are never less than one cycle apart, so `delta >= cooldown` never suppresses. The
 * floor is now two cycles, and these tests assert the property that makes it work rather than the
 * number.
 */
class AntiDupeSettingsTest {

    @Test
    void aCooldownShorterThanOneScanCycleIsRaisedAboveTheCycle() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("anti-dupe.detection-cooldown-ms", 5_000L);
        config.set("performance.inventory-scan-interval", 600L);
        // One cycle is 30,000 ms. Exactly one cycle would still be inert, so the floor is two.
        assertEquals(60_000L, AntiDupeSettings.from(config).detectionCooldown());
    }

    @Test
    void theFloorLeavesHeadroomOverOneCycleRatherThanLandingOnIt() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("anti-dupe.detection-cooldown-ms", 0L);
        config.set("performance.inventory-scan-interval", 600L);
        long cycle = 30_000L;
        assertTrue(
            AntiDupeSettings.from(config).detectionCooldown() > cycle,
            "a floor of exactly one cycle cannot suppress a repeat: the test is strict and two "
                + "audits are >= one cycle apart"
        );
    }

    @Test
    void aCooldownLongerThanTheCycleIsHonoured() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("anti-dupe.detection-cooldown-ms", 3_600_000L);
        config.set("performance.inventory-scan-interval", 600L);
        assertEquals(3_600_000L, AntiDupeSettings.from(config).detectionCooldown());
    }

    @Test
    void aDisabledOrNonsenseCooldownStillGetsTheFloor() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("anti-dupe.detection-cooldown-ms", 0L);
        config.set("performance.inventory-scan-interval", 200L);
        assertEquals(20_000L, AntiDupeSettings.from(config).detectionCooldown());
    }

    @Test
    void aSlowerScanIntervalRaisesTheFloorWithIt() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("anti-dupe.detection-cooldown-ms", 5_000L);
        config.set("performance.inventory-scan-interval", 2_400L);
        assertEquals(240_000L, AntiDupeSettings.from(config).detectionCooldown());
    }

    @Test
    void anAbsurdScanIntervalDoesNotOverflowIntoANegativeFloor() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("anti-dupe.detection-cooldown-ms", 0L);
        config.set("performance.inventory-scan-interval", Long.MAX_VALUE);
        assertTrue(AntiDupeSettings.from(config).detectionCooldown() > 0L);
    }

    /**
     * The shipped file, not a synthetic one: the value an admin actually receives must be able to
     * do its job, and a default that cannot is the defect this test exists for.
     */
    @Test
    void theShippedConfigNeverShipsAnInertCooldown() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/config.yml")) {
            var config = YamlConfiguration.loadConfiguration(
                new InputStreamReader(input, StandardCharsets.UTF_8));
            long cycle = config.getLong("performance.inventory-scan-interval", 600L) * 50L;
            assertTrue(
                AntiDupeSettings.from(config).detectionCooldown() > cycle,
                "shipped detection-cooldown-ms must exceed one scan cycle (" + cycle
                    + " ms), or it can never suppress a repeat"
            );
        }
    }
}
