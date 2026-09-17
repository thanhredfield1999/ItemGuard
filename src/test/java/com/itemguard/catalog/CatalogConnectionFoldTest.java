package com.itemguard.catalog;

import com.itemguard.persistence.SqliteConnectionOwner;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogConnectionFoldTest {
    @TempDir Path dir;

    @Test void dedicatedFunctionSurvivesCatalogCleanupAndReopen() throws Exception {
        Path db = dir.resolve("fold.db");
        for (int opening = 0; opening < 2; opening++) {
            try (var owner = new SqliteConnectionOwner(db)) {
                boolean registered = owner.call(c -> {
                    try (var s = c.createStatement(); var r = s.executeQuery(
                        "SELECT count(*) FROM pragma_function_list WHERE name='ig_catalog_index_fold_v1' AND narg=1")) {
                        return r.next() && r.getInt(1) == 1;
                    }
                });
                assertTrue(registered, "dedicated index fold must be registered on each connection");
                new CatalogRepository(owner).find(new CatalogQuery("", "", CatalogCategory.ALL, "")).get();
                String folded = owner.call(c -> {
                    try (var s = c.createStatement(); var r = s.executeQuery(
                        "SELECT ig_catalog_index_fold_v1('KIẾM'), ig_catalog_index_fold_v1(NULL)")) {
                        assertTrue(r.next()); assertEquals("", r.getString(2)); return r.getString(1);
                    }
                });
                assertEquals("kiếm", folded);
            }
        }
    }
}
