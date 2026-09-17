package com.itemguard.catalog;

import com.itemguard.persistence.SqliteConnectionOwner;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogBackfillTest {
    @TempDir Path dir;
    @Test void interruptedSqlRollsBackSchemaAndPreservesSource() {
        try (var owner = new SqliteConnectionOwner(dir.resolve("interrupted.db"))) {
            owner.call(c -> {
                try(var p=c.prepareStatement("INSERT INTO tracked_items(code,item_uuid,item_name,created_at,last_seen_at) VALUES(?,?,?,0,0)")) {
                    for(int i=0;i<1000;i++){p.setString(1,"C"+i);p.setString(2,"U"+i);p.setString(3,"Original"+i);p.addBatch();}
                    p.executeBatch();
                }
                return null;
            });
            var failure=assertThrows(IllegalStateException.class, () -> owner.call(c -> {CatalogIndexSchema.backfill(c,1);return null;}));
            assertTrue(failure.getCause().getMessage().contains("SQLITE_INTERRUPT"));
            owner.call(c -> {
                try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM tracked_items")){assertTrue(r.next());assertEquals(1000,r.getInt(1));}
                try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='catalog_search_documents'")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
                return null;
            });
        }
    }
    @Test void backfillsExistingRecordsAndRollsBackBudgetFailure() {
        try (var owner = new SqliteConnectionOwner(dir.resolve("backfill.db"))) {
            owner.call(c -> {
                try (var s = c.createStatement()) {
                    s.executeUpdate("INSERT INTO tracked_items(code,item_uuid,item_name,created_at,last_seen_at) VALUES('ABC','uuid','Original',0,0)");
                }
                return null;
            });
            assertThrows(IllegalStateException.class, () -> owner.call(c -> {
                CatalogIndexSchema.backfill(c, 0); return null;
            }));
            int objects = owner.call(c -> {
                try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='catalog_search_documents'")){r.next();return r.getInt(1);}
            });
            assertEquals(0, objects);
            owner.call(c -> { CatalogIndexSchema.backfill(c, 100000); return null; });
            int hits = owner.call(c -> {
                try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM catalog_search_fts WHERE catalog_search_fts MATCH 'original'")){r.next();return r.getInt(1);}
            });
            assertEquals(1, hits, "backfill must include existing canonical rows");
        }
    }
}
