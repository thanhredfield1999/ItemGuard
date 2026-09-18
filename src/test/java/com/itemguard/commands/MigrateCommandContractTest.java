package com.itemguard.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrateCommandContractTest {
    @Test
    void migrateIsAnAdminDryRunFirstCommand() throws Exception {
        String main = Files.readString(Path.of("src/main/java/com/itemguard/commands/MainCommand.java"));
        String manifest = Files.readString(Path.of("src/main/resources/plugin.yml"));
        String config = Files.readString(Path.of("src/main/resources/config.yml"));

        assertTrue(main.contains("case \"migrate\""));
        assertTrue(main.contains("itemguard.migrate"));
        assertTrue(manifest.contains("itemguard.migrate"));
        assertTrue(config.contains("database:"));
        assertTrue(config.contains("mysql:"));
    }
}
