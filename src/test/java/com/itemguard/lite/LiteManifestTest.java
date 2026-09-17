package com.itemguard.lite;

import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class LiteManifestTest {
    @Test void liteDescriptorHasOnlyReadOnlyCommandEntryPointAndSamePdcNamespace() throws Exception {
        try (var input = getClass().getResourceAsStream("/lite/plugin.yml")) {
            assertNotNull(input);
            var descriptor = new PluginDescriptionFile(input);
            assertEquals("ItemGuard", descriptor.getName());
            assertEquals(ItemGuardLite.class.getName(), descriptor.getMain());
            // The declared floor, not the compile target. LITE is built against the newest
            // paper-api but only uses API that already existed in 1.21.4, so it is allowed to
            // load on anything from 1.21.4 up. Lowering this without re-running the version
            // matrix would be advertising compatibility nobody measured.
            assertEquals("1.21.4", descriptor.getAPIVersion());
            assertEquals(java.util.Set.of("itemguard"), descriptor.getCommands().keySet());
            assertTrue(descriptor.getSoftDepend().isEmpty());
            assertTrue(descriptor.getPermissions().stream().noneMatch(p ->
                p.getName().contains("finditem") || p.getName().contains("matdo") || p.getName().contains("reclaim")));
        }
    }
    @Test void liteDefaultsToEnglishAndRetainsAuditHistory() throws Exception {
        try (var input = getClass().getResourceAsStream("/lite/config.yml")) {
            assertNotNull(input);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            assertEquals("en", config.getString("general.language"));
            assertEquals(0, config.getInt("performance.auto-cleanup.interval-hours"));
            assertFalse(config.getBoolean("discord.enabled"));
            assertFalse(config.getBoolean("worlds.worldguard-support"));
            assertTrue(config.getBoolean("anti-dupe.enabled"));
            assertEquals("NOTIFY", config.getString("anti-dupe.action"));
        }
    }
}
