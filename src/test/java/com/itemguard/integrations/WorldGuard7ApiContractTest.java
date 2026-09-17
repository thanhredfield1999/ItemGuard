package com.itemguard.integrations;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGuard7ApiContractTest {

    @Test
    void pomPinsProvidedWorldGuardSevenApi() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertTrue(pom.contains("<id>enginehub</id>"));
        assertTrue(pom.contains("<artifactId>worldguard-bukkit</artifactId>"));
        assertTrue(pom.contains("<version>7.0.16</version>"));
        assertTrue(pom.contains("<scope>provided</scope>"));
    }

    @Test
    void hookUsesWorldGuardSevenPlayerAdapterWithoutLegacyReflection() throws IOException {
        String hook = Files.readString(
            Path.of("src/main/java/com/itemguard/integrations/WorldGuardHook.java"),
            StandardCharsets.UTF_8
        );
        String adapter = Files.readString(
            Path.of("src/main/java/com/itemguard/integrations/WorldGuard7PermissionQuery.java"),
            StandardCharsets.UTF_8
        );

        assertTrue(adapter.contains("WorldGuardPlugin.inst().wrapPlayer(player)"));
        assertTrue(adapter.contains("hasPermission(\"itemguard.track\")"));
        assertFalse(hook.contains("getRegionContainer"));
        assertFalse(hook.contains("createPlayer"));
        assertFalse(hook.contains("java.lang.reflect"));
    }
}