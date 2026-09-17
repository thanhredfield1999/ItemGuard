package com.itemguard.commands;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MainCommandUxContractTest {

    @Test
    void helpAndUsageExposeBrowserCapability() throws Exception {
        String main = Files.readString(Path.of(
            "src/main/java/com/itemguard/commands/MainCommand.java"
        ));
        String manifest = Files.readString(Path.of("src/main/resources/plugin.yml"));

        assertTrue(main.contains("/itemguard browser [player]"));
        assertTrue(main.contains("check|history|search|stats|browser|reload|info"));
        assertTrue(manifest.contains("check|history|search|stats|browser|reload"));
    }

    @Test
    void infoDatabaseReadIsAsync() throws Exception {
        String main = Files.readString(Path.of(
            "src/main/java/com/itemguard/commands/MainCommand.java"
        ));

        assertTrue(main.contains("getStatsAsync()"));
        assertTrue(main.contains("UiMainThreadHandoff.dispatch(plugin"));
        assertFalse(main.contains("Bukkit.getScheduler().runTask(plugin"));
    }
}