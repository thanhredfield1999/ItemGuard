package com.itemguard.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CheckCommandAsyncContractTest {
    @Test
    void checkCommandDoesNotBlockTheServerThreadOnDatabaseReads() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/commands/CheckCommand.java"));

        assertTrue(source.contains("getItemAsync(code)"),
            "check must use the repository async item read");
        assertTrue(source.contains("getHistoryCountAsync(code)"),
            "check must use the repository async history-count read");
        assertTrue(source.contains("UiMainThreadHandoff.dispatch"),
            "rendering must return to the Bukkit main thread");
        assertFalse(source.contains("getItem(code)"),
            "check must not block on synchronous item lookup");
        assertFalse(source.contains("getHistoryCount(code)"),
            "check must not block on synchronous history lookup");
    }
}
