package com.itemguard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManifestContractTest {

    @Test
    void manifestDeclaresAdminFindItemCommandAndPermission() throws IOException {
        String manifest = resource("/plugin.yml");

        assertTrue(manifest.contains("  finditem:\n"));
        assertTrue(manifest.contains("itemguard.finditem.admin: true"));
        assertTrue(manifest.contains("  itemguard.finditem.admin:\n"));
        assertTrue(manifest.contains("    default: op\n"));
    }

    @Test
    void manifestDeclaresMemberMatDoCommandAndPermission() throws IOException {
        String manifest = resource("/plugin.yml");

        assertTrue(manifest.contains("  matdo:\n"));
        assertTrue(manifest.contains("itemguard.matdo: true"));
        assertTrue(manifest.contains("  itemguard.matdo:\n"));
        assertTrue(manifest.contains("    default: true\n"));
    }

    @Test
    void manifestLoadsAfterOptionalWorldGuardDependency() throws IOException {
        String manifest = resource("/plugin.yml");

        assertTrue(manifest.contains("softdepend: [WorldGuard]\n"));
    }

    @Test
    void manifestDeclaresWorldGuardTrackPermissionFailClosedByDefault() throws IOException {
        String manifest = resource("/plugin.yml");

        assertTrue(manifest.contains("itemguard.track: true"));
        assertTrue(manifest.contains("  itemguard.track:\n"));
        assertTrue(manifest.contains("    default: op\n"));
    }

    @Test
    void manifestSeparatesSelfHistoryFromStaffInvestigation() throws IOException {
        String manifest = resource("/plugin.yml");

        assertTrue(manifest.contains("itemguard.history.others: true"));
        assertTrue(permissionBlock(manifest, "itemguard.history.others").contains("default: op"));
        assertTrue(permissionBlock(manifest, "itemguard.gui").contains("default: op"));
    }

    @Test
    void liteManifestIsPublicReleaseMetadata() throws IOException {
        String manifest = resource("/lite/plugin.yml");

        assertFalse(manifest.contains("-test"));
        assertFalse(manifest.toLowerCase().contains("test candidate"));
        assertTrue(manifest.contains("version: '1.0.0-lite'"));

        // The description line is what an admin reads in /plugins and in the server log, so it
        // has to state the platforms the jar was actually verified on. It said "Paper only"
        // while the same jar was passing on Spigot and Purpur — a user on either would have
        // concluded they were running it wrong. Assert the meaning, not one frozen sentence:
        // every supported platform present, and no platform claimed exclusively.
        String description = manifest.lines()
            .filter(line -> line.startsWith("description:"))
            .findFirst()
            .orElse("");
        assertTrue(description.contains("Paper"), description);
        assertTrue(description.contains("Purpur"), description);
        assertTrue(description.contains("Spigot"), description);
        // Match "<Platform> only", not the word "only" anywhere — "read-only history" is a
        // legitimate phrase and an earlier version of this assertion rejected it.
        assertFalse(
            description.matches(".*\\b(Paper|Purpur|Spigot)[- ]only\\b.*"),
            description
        );
    }

    private String permissionBlock(String manifest, String permission) {
        int start = manifest.indexOf("  " + permission + ":\n");
        if (start < 0) return "";
        int next = start + 1;
        while ((next = manifest.indexOf("\n  ", next)) >= 0) {
            int content = next + 3;
            if (content < manifest.length() && manifest.charAt(content) != ' ') {
                break;
            }
            next = content;
        }
        return next < 0 ? manifest.substring(start) : manifest.substring(start, next);
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        }
    }
}
