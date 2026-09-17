package com.itemguard.catalog;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogMatchTest {
    @Test void sqliteMatchesDuplicateNamesWithoutInterpretingOperators() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:");
             var s = c.createStatement()) {
            s.execute("CREATE VIRTUAL TABLE names USING fts5(name, tokenize='trigram case_sensitive 1')");
            try (var p = c.prepareStatement("INSERT INTO names(name) VALUES(?)")) {
                for (String name : java.util.List.of("OR *", "OR *", "A\"B", "other")) {
                    p.setString(1, CatalogText.fold(name)); p.executeUpdate();
                }
            }
            try (var p = c.prepareStatement("SELECT count(*) FROM names WHERE names MATCH ?")) {
                p.setString(1, CatalogMatch.literal("OR *").orElseThrow());
                try (var r = p.executeQuery()) { assertTrue(r.next()); assertEquals(2, r.getInt(1)); }
                p.setString(1, CatalogMatch.literal("A\"B").orElseThrow());
                try (var r = p.executeQuery()) { assertTrue(r.next()); assertEquals(1, r.getInt(1)); }
            }
        }
    }

    @Test void quotesLiteralAndNormalizesBeforeCountingCodepoints() {
        assertEquals(java.util.Optional.of("\"a\"\"b\""), CatalogMatch.literal("A\"B"));
        assertTrue(CatalogMatch.literal("😀雪").isEmpty());
        assertTrue(CatalogMatch.literal("e\u0301x").isEmpty());
        assertEquals("\"i\u0307x\"", CatalogMatch.literal("İX").orElseThrow());
        assertEquals("\"or *\"", CatalogMatch.literal("OR *").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> CatalogMatch.literal("a\0b"));
    }
}
