package com.itemguard.catalog;

import com.itemguard.persistence.SqliteConnectionOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CatalogRepositoryTest {
    @TempDir Path dir;

    @Test
    void pagesPastOldCapsWithoutDuplicateOrMissingCodes() throws Exception {
        try (var owner = new SqliteConnectionOwner(dir.resolve("catalog.db"))) {
            seed(owner, 241);
            var repo = new CatalogRepository(owner);
            var codes = new HashSet<String>();
            String cursor = "";
            int pages = 0;
            while (true) {
                var page = repo.find(new CatalogQuery("", "", CatalogCategory.ALL, cursor)).get(5, TimeUnit.SECONDS);
                assertFalse(page.items().isEmpty(), "catalog must return persisted records");
                assertTrue(page.items().size() <= 36);
                for (var row : page.items()) assertTrue(codes.add(row.code()), "no duplicate cursor rows");
                pages++;
                if (!page.hasMore()) break;
                cursor = page.items().getLast().code();
                assertTrue(pages < 10);
            }
            assertEquals(241, codes.size());
            assertEquals(7, pages);
        }
    }

    @Test
    void filtersCombineAndTreatSearchAsLiteralNotWildcardOrSql() throws Exception {
        try (var owner = new SqliteConnectionOwner(dir.resolve("filters.db"))) {
            seed(owner, 12);
            var repo = new CatalogRepository(owner);
            assertEquals(1, repo.find(new CatalogQuery("100%_safe", "Lan", CatalogCategory.SWORD, "")).get().items().size());
            assertEquals(6, repo.find(new CatalogQuery("", "", CatalogCategory.ARMOR, "")).get().items().size());
            assertTrue(repo.find(new CatalogQuery("", "Minh", CatalogCategory.SWORD, "")).get().items().isEmpty());
            assertEquals("C00002", repo.find(new CatalogQuery("c00002", "lan", CatalogCategory.ALL, "")).get().items().getFirst().code());
            assertTrue(repo.find(new CatalogQuery("' OR 1=1 --", "", CatalogCategory.ALL, "")).get().items().isEmpty());
            assertEquals(1, repo.find(new CatalogQuery("%", "", CatalogCategory.ALL, "")).get().items().size());
        }
    }

    @Test
    void refusesInvalidInputRatherThanSilentlyBroadeningQuery() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogQuery("a\0b", "", CatalogCategory.ALL, ""));
        assertThrows(IllegalArgumentException.class, () -> new CatalogQuery("", "a\0b", CatalogCategory.ALL, ""));
        assertThrows(IllegalArgumentException.class, () -> new CatalogQuery("", "", CatalogCategory.ALL, "a\0b"));
        assertThrows(IllegalArgumentException.class, () -> new CatalogQuery("x".repeat(65), "", CatalogCategory.ALL, ""));
        assertThrows(IllegalArgumentException.class, () -> new CatalogQuery("", "x".repeat(17), CatalogCategory.ALL, ""));
        assertThrows(IllegalArgumentException.class, () -> new CatalogQuery("a\nb", "", CatalogCategory.ALL, ""));
        assertThrows(IllegalArgumentException.class, () -> new CatalogQuery("", "", CatalogCategory.ALL, "x".repeat(17)));
        assertThrows(NullPointerException.class, () -> new CatalogQuery("", "", null, ""));
    }

    @Test
    void budgetExhaustionIsErrorAndDoesNotPoisonNextOwnerOperation() throws Exception {
        try (var owner = new SqliteConnectionOwner(dir.resolve("budget.db"))) {
            seed(owner, 241);
            var limited = new CatalogRepository(owner, 1);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> limited.find(new CatalogQuery("no match", "", CatalogCategory.ALL, "")).get());
            assertNotNull(failure.getCause());
            int count = owner.call(c -> {
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM tracked_items")) {
                    r.next(); return r.getInt(1);
                }
            });
            assertEquals(241, count);
            assertEquals(36, new CatalogRepository(owner).find(new CatalogQuery("", "", CatalogCategory.ALL, "")).get().items().size());
        }
    }

    @Test
    void historyIsNewestHundredAndBoundToExactIdentity() throws Exception {
        try (var owner = new SqliteConnectionOwner(dir.resolve("history.db"))) {
            owner.call(c -> {
                try (var s = c.prepareStatement("INSERT INTO item_history(code,item_uuid,action,timestamp) VALUES('ABC123',?,'DROP',?)")) {
                    for (int i = 0; i < 120; i++) {
                        s.setString(1, "identity-1"); s.setLong(2, i); s.addBatch();
                    }
                    s.setString(1, "identity-2"); s.setLong(2, 999); s.addBatch(); s.executeBatch();
                }
                return null;
            });
            var events = new CatalogRepository(owner).history("ABC123", "identity-1").get();
            assertEquals(100, events.size(), "bounded history must load real rows");
            assertEquals(119, events.getFirst().timestamp());
            assertEquals(20, events.getLast().timestamp());
            assertThrows(UnsupportedOperationException.class, () -> events.clear());
        }
    }

    @Test void vietnameseTextSearchIgnoresCase() throws Exception {
        try(var owner=new SqliteConnectionOwner(dir.resolve("unicode.db"))) {
            seed(owner,3);
            var page=new CatalogRepository(owner).find(new CatalogQuery("kiếm của lan","",CatalogCategory.ALL,"")).get();
            assertEquals(1,page.items().size(),"Vietnamese search must ignore case");
        }
    }

    private void seed(SqliteConnectionOwner owner, int count) {
        owner.call(c -> {
            try (var s = c.prepareStatement("INSERT INTO tracked_items (code,item_uuid,material,item_name,owner_name,last_seen_at,created_at) VALUES (?,?,?,?,?,?,0)")) {
                for (int i = 0; i < count; i++) {
                    s.setString(1, String.format("C%05d", i));
                    s.setString(2, UUID.randomUUID().toString());
                    s.setString(3, i % 2 == 0 ? "DIAMOND_SWORD" : "DIAMOND_HELMET");
                    s.setString(4, i == 0 ? "Blade 100%_safe" : i == 1 ? "KIẾM CỦA LAN" : "Item " + i);
                    s.setString(5, i % 2 == 0 ? "Lan" : "Minh");
                    s.setLong(6, 1000L + i);
                    s.addBatch();
                }
                s.executeBatch();
            }
            return null;
        });
    }
}
