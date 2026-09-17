package com.itemguard.catalog;

/** Offline integration candidate; not installed by production startup. */
final class CatalogIndexSchema {
    private CatalogIndexSchema() {}
    static void backfill(java.sql.Connection connection, int progressLimit) throws java.sql.SQLException {
        if (connection.getAutoCommit()) throw new java.sql.SQLException("Backfill requires transaction");
        if (progressLimit <= 0) throw new java.sql.SQLException("Backfill budget exhausted");
        long started = System.nanoTime();
        org.sqlite.ProgressHandler.setHandler(connection, 1000, new org.sqlite.ProgressHandler() {
            private int calls;
            @Override protected int progress() {
                return ++calls >= progressLimit || System.nanoTime() - started > 30_000_000_000L ? 1 : 0;
            }
        });
        Throwable original = null;
        try {
            createTables(connection);
            try (var s = connection.createStatement()) {
                s.executeUpdate("INSERT INTO catalog_search_documents(code,item_uuid) SELECT code,item_uuid FROM tracked_items ORDER BY code");
                s.executeUpdate("""
                    INSERT INTO catalog_search_fts(rowid,code,name,material)
                    SELECT d.id,ig_catalog_index_fold_v1(t.code),ig_catalog_index_fold_v1(t.item_name),ig_catalog_index_fold_v1(t.material)
                    FROM tracked_items t JOIN catalog_search_documents d ON d.code=t.code AND d.item_uuid=t.item_uuid
                    """);
            }
        } catch (java.sql.SQLException | RuntimeException | Error failure) {
            original = failure; throw failure;
        } finally {
            try { org.sqlite.ProgressHandler.clearHandler(connection); }
            catch (java.sql.SQLException cleanup) { if (original != null) original.addSuppressed(cleanup); else throw cleanup; }
        }
    }
    static void createEmpty(java.sql.Connection connection) throws java.sql.SQLException {
        if (connection.getAutoCommit()) throw new java.sql.SQLException("Index creation requires transaction");
        try (var s = connection.createStatement(); var rows = s.executeQuery("SELECT 1 FROM tracked_items LIMIT 1")) {
            if (rows.next()) throw new java.sql.SQLException("Existing catalog records require backfill");
        }
        createTables(connection);
    }

    private static void createTables(java.sql.Connection connection) throws java.sql.SQLException {
        try (var s = connection.createStatement()) {
            s.execute("CREATE TABLE catalog_search_documents(id INTEGER PRIMARY KEY, code TEXT NOT NULL UNIQUE, item_uuid TEXT NOT NULL UNIQUE)");
            s.execute("CREATE VIRTUAL TABLE catalog_search_fts USING fts5(code,name,material,tokenize='trigram case_sensitive 1')");
            s.execute("""
                CREATE TRIGGER catalog_search_identity BEFORE UPDATE OF code,item_uuid ON tracked_items
                WHEN new.code IS NOT old.code OR new.item_uuid IS NOT old.item_uuid
                BEGIN SELECT RAISE(ABORT,'Catalog identity mutation is unsupported'); END
                """);
            s.execute("""
                CREATE TRIGGER catalog_search_insert AFTER INSERT ON tracked_items BEGIN
                  INSERT INTO catalog_search_documents(code,item_uuid) VALUES(new.code,new.item_uuid);
                  INSERT INTO catalog_search_fts(rowid,code,name,material)
                  SELECT id,ig_catalog_index_fold_v1(new.code),ig_catalog_index_fold_v1(new.item_name),ig_catalog_index_fold_v1(new.material)
                  FROM catalog_search_documents WHERE code=new.code AND item_uuid=new.item_uuid;
                END
                """);
            s.execute("""
                CREATE TRIGGER catalog_search_update AFTER UPDATE OF item_name,material ON tracked_items BEGIN
                  DELETE FROM catalog_search_fts WHERE rowid IN
                    (SELECT id FROM catalog_search_documents WHERE code=old.code AND item_uuid=old.item_uuid);
                  INSERT INTO catalog_search_fts(rowid,code,name,material)
                  SELECT id,ig_catalog_index_fold_v1(new.code),ig_catalog_index_fold_v1(new.item_name),ig_catalog_index_fold_v1(new.material)
                  FROM catalog_search_documents WHERE code=new.code AND item_uuid=new.item_uuid;
                END
                """);
            s.execute("""
                CREATE TRIGGER catalog_search_delete AFTER DELETE ON tracked_items BEGIN
                  DELETE FROM catalog_search_fts WHERE rowid IN
                    (SELECT id FROM catalog_search_documents WHERE code=old.code AND item_uuid=old.item_uuid);
                  DELETE FROM catalog_search_documents WHERE code=old.code AND item_uuid=old.item_uuid;
                END
                """);
        }
    }
}
