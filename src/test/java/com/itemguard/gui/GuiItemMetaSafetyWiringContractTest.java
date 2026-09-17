package com.itemguard.gui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiItemMetaSafetyWiringContractTest {

    @Test
    void browserClicksIgnoreMetaLessItemsFailClosed() throws Exception {
        String listener = Files.readString(Path.of(
            "src/main/java/com/itemguard/gui/GUIListener.java"
        ));

        assertFalse(listener.contains("clicked.getItemMeta().getPersistentDataContainer()"));
        assertTrue(occurrences(listener, "if (meta == null) return;") >= 2);
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = text.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }
}
