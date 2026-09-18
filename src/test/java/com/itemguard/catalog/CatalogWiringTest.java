package com.itemguard.catalog;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Supplemental wiring evidence; not Paper execution. */
class CatalogWiringTest {
    @Test void defaultBrowserUsesReadonlyCatalogAndLifecycleIsRegistered() throws Exception {
        String command=read("commands/MainCommand.java");
        assertTrue(command.contains("getCatalogUi().open(player)"),"default browser must reach new catalog");
        String plugin=read("ItemGuard.java");
        assertTrue(plugin.contains("registerEvents(catalogUi, this)"));
        assertTrue(plugin.contains("catalogUi.clear()"));
        String database = read("data/DatabaseManager.java");
        assertTrue(database.contains("new CatalogRepository(sqlite)"));
        assertTrue(database.contains("new MySqlCatalogRepository(mysql)"));
    }
    private String read(String file) throws Exception { return Files.readString(Path.of("src/main/java/com/itemguard/"+file)); }
}
