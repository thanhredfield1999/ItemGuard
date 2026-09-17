package com.itemguard.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemCodeInputTest {

    @Test
    void normalizesCodeCopiedFromGui() {
        assertEquals("AB12CD", ItemCodeInput.normalize(" #ab12cd "));
        assertEquals("TEST-1234", ItemCodeInput.normalize("#TEST-1234"));
    }

    @Test
    void rejectsBlankOrMarkerOnlyCode() {
        assertThrows(IllegalArgumentException.class, () -> ItemCodeInput.normalize("  "));
        assertThrows(IllegalArgumentException.class, () -> ItemCodeInput.normalize(" # "));
    }
}
