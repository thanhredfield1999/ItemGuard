package com.itemguard.gui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiAsyncReadWiringContractTest {

    @Test
    void searchAndStatsUseAsyncDatabaseReads() throws Exception {
        String search = source("commands/SearchCommand.java");
        String stats = source("commands/StatsCommand.java");

        assertTrue(search.contains("getItemAsync("));
        assertTrue(search.contains("getItemsByPlayerAsync("));
        assertTrue(search.contains("searchItemsAsync("));
        assertTrue(search.contains("UiMainThreadHandoff.dispatch(plugin"));
        assertFalse(search.contains("Bukkit.getScheduler().runTask(plugin"));
        assertFalse(search.contains("plugin.getDB().getItem("));
        assertFalse(search.contains("plugin.getDB().getItemsByPlayer("));
        assertFalse(search.contains("plugin.getDB().searchItems("));

        assertTrue(stats.contains("getStatsAsync()"));
        assertTrue(stats.contains("UiMainThreadHandoff.dispatch(plugin"));
        assertFalse(stats.contains("Bukkit.getScheduler().runTask(plugin"));
        assertFalse(stats.contains("plugin.getDB().getStats()"));
    }

    @Test
    void guiReadsAreAsyncAndPlayerHeadsDoNotRunNPlusOneQueries() throws Exception {
        String listener = source("gui/GUIListener.java");
        String history = source("gui/HistoryGUI.java");
        String filter = source("gui/FilterChatListener.java");
        String browser = source("gui/PlayerBrowserGUI.java");

        assertTrue(listener.contains("getHistoryAsync("));
        assertTrue(listener.contains("getItemsByPlayerAsync("));
        assertTrue(listener.contains("UiMainThreadHandoff.dispatch("));
        assertFalse(listener.contains("getHistory(codeVal, 100)"));
        assertFalse(listener.contains("getItemsByPlayer(uuid)"));

        assertTrue(history.contains("getItemsByPlayerAsync("));
        assertTrue(history.contains("UiMainThreadHandoff.dispatch("));
        assertFalse(history.contains("getItemsByPlayer(gui.parentPlayerUuid)"));

        assertTrue(filter.contains("getItemsByPlayerAsync("));
        assertTrue(filter.contains("UiMainThreadHandoff.dispatch("));
        assertFalse(filter.contains("getItemsByPlayer(req.uuid)"));

        assertFalse(browser.contains("plugin.getDB().getItemsByPlayer(p.getUniqueId())"));
    }

    private String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/itemguard/" + relative));
    }
}
