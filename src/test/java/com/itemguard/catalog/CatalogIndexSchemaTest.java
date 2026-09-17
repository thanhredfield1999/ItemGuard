package com.itemguard.catalog;

import com.itemguard.persistence.SqliteConnectionOwner;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogIndexSchemaTest {
    @TempDir Path dir;

    @Test void refusesInstallingEmptyIndexOverExistingRecords() {
        try (var owner = new SqliteConnectionOwner(dir.resolve("existing.db"))) {
            owner.call(c -> {
                try (var s = c.createStatement()) {
                    s.executeUpdate("INSERT INTO tracked_items(code,item_uuid,item_name,created_at,last_seen_at) VALUES('ABC','identity','Original',0,0)");
                }
                return null;
            });
            assertThrows(IllegalStateException.class, () -> owner.call(c -> {
                CatalogIndexSchema.createEmpty(c); return null;
            }), "existing records require backfill, not an empty index");
            int tables = owner.call(c -> {
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='catalog_search_documents'")) {
                    r.next(); return r.getInt(1);
                }
            });
            assertEquals(0, tables);
        }
    }

    @Test void rejectsIdentityMutationWithoutLosingIndexedRecord() {
        try (var owner = new SqliteConnectionOwner(dir.resolve("identity.db"))) {
            owner.call(c -> {
                CatalogIndexSchema.createEmpty(c);
                try (var s = c.createStatement()) {
                    s.executeUpdate("INSERT INTO tracked_items(code,item_uuid,item_name,created_at,last_seen_at) VALUES('ABC','identity','Original',0,0)");
                }
                return null;
            });
            for (String sql : java.util.List.of(
                "UPDATE tracked_items SET code='DEF' WHERE code='ABC'",
                "UPDATE tracked_items SET item_uuid='different' WHERE code='ABC'")) {
                assertThrows(IllegalStateException.class, () -> owner.call(c -> {
                    try (var s = c.createStatement()) { s.executeUpdate(sql); }
                    return null;
                }), "identity changes must be rejected");
                assertEquals(1, hits(owner, "original"));
            }
        }
    }

    @Test void maintainsIndexAcrossWritesAndRollback() {
        try (var owner = new SqliteConnectionOwner(dir.resolve("writes.db"))) {
            owner.call(c -> { CatalogIndexSchema.createEmpty(c); return null; });
            owner.call(c -> {
                try (var s = c.createStatement()) {
                    s.executeUpdate("INSERT INTO tracked_items(code,item_uuid,item_name,created_at,last_seen_at) VALUES('ABC','identity','Original',0,0)");
                }
                return null;
            });
            assertEquals(1, hits(owner, "original"), "insert must update FTS");
            assertThrows(IllegalStateException.class, () -> owner.call(c -> {
                try (var s = c.createStatement()) { s.executeUpdate("UPDATE tracked_items SET item_name='Changed' WHERE code='ABC'"); }
                throw new IllegalStateException("rollback injected");
            }));
            assertEquals(1, hits(owner, "original"));
            assertEquals(0, hits(owner, "changed"));
            owner.call(c -> { try (var s = c.createStatement()) { s.executeUpdate("UPDATE tracked_items SET item_name='Changed' WHERE code='ABC'"); } return null; });
            assertEquals(1, hits(owner, "changed"));
            assertEquals(0, hits(owner, "original"));
            owner.call(c -> { try (var s = c.createStatement()) { s.executeUpdate("DELETE FROM tracked_items WHERE code='ABC'"); } return null; });
            assertEquals(0, hits(owner, "changed"));
        }
    }

    private int hits(SqliteConnectionOwner owner, String term) {
        return owner.call(c -> {
            try (var p = c.prepareStatement("SELECT count(*) FROM catalog_search_fts WHERE catalog_search_fts MATCH ?")) {
                p.setString(1, CatalogMatch.literal(term).orElseThrow());
                try (var r = p.executeQuery()) { r.next(); return r.getInt(1); }
            }
        });
    }

    @Test void createsTransactionalDerivedIndex() {
        try (var owner = new SqliteConnectionOwner(dir.resolve("index.db"))) {
            owner.call(c -> { CatalogIndexSchema.createEmpty(c); return null; });
            int tables = owner.call(c -> {
                try (var s = c.createStatement(); var r = s.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE name IN ('catalog_search_documents','catalog_search_fts')")) {
                    r.next(); return r.getInt(1);
                }
            });
            assertEquals(2, tables, "document mapping and FTS must both exist");
        }
    }
}
