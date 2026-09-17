package com.itemguard.catalog;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogTextTest {
    @Test void preservesExistingNormalizationContract() {
        assertEquals("kiếm rồng", CatalogText.fold("KIẾM RỒNG"));
        assertEquals("i\u0307", CatalogText.fold("İ"));
        assertEquals("οσος", CatalogText.fold("ΟΣΟΣ"));
        assertEquals("", CatalogText.fold(""));
        assertEquals("a\0b", CatalogText.fold("A\0B"));
        assertThrows(NullPointerException.class, () -> CatalogText.fold(null));
    }
}
