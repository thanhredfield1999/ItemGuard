package com.itemguard.commands;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectCommandPermissionContractTest {

    @Test
    void directSearchAndStatsAliasesDeclareCapabilityPermissions() throws Exception {
        String manifest = Files.readString(Path.of("src/main/resources/plugin.yml"))
            .replace("\r\n", "\n");

        assertTrue(commandBlock(manifest, "igsearch").contains("permission: itemguard.search"));
        assertTrue(commandBlock(manifest, "igstats").contains("permission: itemguard.stats"));
    }

    @Test
    void directExecutorsCheckPermissionBeforeWork() throws Exception {
        String search = Files.readString(Path.of(
            "src/main/java/com/itemguard/commands/SearchCommand.java"
        ));
        String stats = Files.readString(Path.of(
            "src/main/java/com/itemguard/commands/StatsCommand.java"
        ));

        assertBefore(search, "hasPermission(\"itemguard.search\")", "searchItems(sender, args)");
        assertBefore(stats, "hasPermission(\"itemguard.stats\")", "showStats(sender)");
    }

    private String commandBlock(String manifest, String command) {
        int start = manifest.indexOf("  " + command + ":\n");
        assertTrue(start >= 0, "missing command " + command);
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

    private void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(firstIndex >= 0, "missing " + first);
        assertTrue(secondIndex > firstIndex, first + " must run before " + second);
    }
}
