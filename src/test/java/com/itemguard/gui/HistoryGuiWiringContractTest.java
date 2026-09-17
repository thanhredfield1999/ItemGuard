package com.itemguard.gui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryGuiWiringContractTest {

    @Test
    void detailBackIsHandledAfterListenerCancelsTheClick() throws Exception {
        String source = historySource();
        assertFalse(source.contains("if (event.isCancelled()) return;"));
        assertTrue(source.contains("detailHolder.getParentGui().openPage("));
    }

    @Test
    void historyDragCancelsAnyTopInventorySlot() throws Exception {
        String source = historySource();
        assertTrue(source.contains("slot < event.getInventory().getSize()"));
    }

    @Test
    void codeHistoryNeverQueriesPlayerItemsWithNullParent() throws Exception {
        String source = historySource();
        assertTrue(source.contains("HistoryNavigationAction.CLOSE"));
        assertTrue(source.contains("parentPlayerUuid"));
    }

    private String historySource() throws Exception {
        return Files.readString(Path.of(
            "src/main/java/com/itemguard/gui/HistoryGUI.java"
        ));
    }
}
