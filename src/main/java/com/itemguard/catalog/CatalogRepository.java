package com.itemguard.catalog;

import com.itemguard.persistence.SqliteConnectionOwner;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.sqlite.ProgressHandler;

/** Bounded SELECT-only catalog; no world scan or item mutation. */
public final class CatalogRepository {
    private final SqliteConnectionOwner owner;
    private final int progressLimit;
    public CatalogRepository(SqliteConnectionOwner owner) { this(owner, 2000); }
    CatalogRepository(SqliteConnectionOwner owner, int progressLimit) {
        this.owner = java.util.Objects.requireNonNull(owner);
        if (progressLimit < 1) throw new IllegalArgumentException("progressLimit");
        this.progressLimit = progressLimit;
    }
    public CompletableFuture<java.util.List<CatalogEvent>> history(String code, String itemUuid) {
        java.util.Objects.requireNonNull(code);
        java.util.Objects.requireNonNull(itemUuid);
        return bounded(connection -> {
            var events = new ArrayList<CatalogEvent>();
            try (var statement = connection.prepareStatement("""
                SELECT id,action,player_name,player_uuid,location,timestamp,additional_data
                FROM item_history WHERE code = ? AND item_uuid = ?
                ORDER BY timestamp DESC, id DESC LIMIT 100
                """)) {
                statement.setString(1, code);
                statement.setString(2, itemUuid);
                try (var result = statement.executeQuery()) {
                    while (result.next()) events.add(new CatalogEvent(result.getLong(1), result.getString(2),
                        result.getString(3), result.getString(4), result.getString(5), result.getLong(6), result.getString(7)));
                }
            }
            return java.util.List.copyOf(events);
        });
    }

    public CompletableFuture<CatalogObservations> observations(String code, String itemUuid) {
        java.util.Objects.requireNonNull(code);
        java.util.Objects.requireNonNull(itemUuid);
        return bounded(connection -> {
            var rows = new ArrayList<CatalogObservation>();
            try (var statement = connection.prepareStatement("""
                SELECT observation_id,scan_epoch,epoch_complete,
                    substr(holder_type,1,64) || CASE WHEN length(holder_type)>64 THEN '… [rút gọn]' ELSE '' END,
                    substr(holder_id,1,255) || CASE WHEN length(holder_id)>255 THEN '… [rút gọn]' ELSE '' END,
                    slot,observed_at
                FROM item_observations WHERE item_uuid = ? AND code = ?
                ORDER BY scan_epoch DESC, observation_id DESC LIMIT 101
                """)) {
                statement.setString(1, itemUuid); statement.setString(2, code);
                try (var result = statement.executeQuery()) {
                    while (result.next()) rows.add(new CatalogObservation(result.getLong(1), result.getLong(2),
                        result.getInt(3) == 1, result.getString(4), result.getString(5), result.getInt(6), result.getLong(7)));
                }
            }
            boolean more = rows.size() > 100;
            if (more) rows.removeLast();
            return new CatalogObservations(rows, more);
        });
    }

    public CompletableFuture<CatalogPage> find(CatalogQuery query) {
        java.util.Objects.requireNonNull(query);
        return bounded(connection -> {
            var rows = new ArrayList<CatalogRow>();
            String sql = """
                SELECT code,item_uuid,material,item_name,owner_name,last_location,last_seen_at
                FROM tracked_items WHERE code > ?
                AND (? = '' OR instr(ig_catalog_fold(COALESCE(item_name,'')), ig_catalog_fold(?)) > 0
                    OR instr(ig_catalog_fold(code),ig_catalog_fold(?)) > 0 OR instr(ig_catalog_fold(COALESCE(material,'')),ig_catalog_fold(?)) > 0)
                AND (? = '' OR owner_name = ? COLLATE NOCASE)
                AND %s ORDER BY code ASC LIMIT 37
                """.formatted(query.category().predicate());
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, query.afterCode());
                for (int i = 2; i <= 5; i++) statement.setString(i, query.text());
                statement.setString(6, query.ownerName());
                statement.setString(7, query.ownerName());
                try (var result = statement.executeQuery()) {
                    while (result.next()) rows.add(new CatalogRow(
                        result.getString(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getString(6), result.getLong(7)));
                }
            }
            boolean more = rows.size() > 36;
            if (more) rows.removeLast();
            return new CatalogPage(rows, more);
        });
    }

    @FunctionalInterface
    interface Cleanup { void run() throws java.sql.SQLException; }

    static void cleanup(Throwable original, Cleanup... actions) throws java.sql.SQLException {
        Throwable first = original;
        for (var action : actions) {
            try { action.run(); }
            catch (java.sql.SQLException failure) {
                if (first == null) first = failure;
                else if (first != failure) first.addSuppressed(failure);
            }
        }
        if (original == null && first != null) throw (java.sql.SQLException) first;
    }

    private <T> CompletableFuture<T> bounded(SqliteConnectionOwner.SqliteOperation<T> operation) {
        return owner.callAsync(connection -> {
            long started = System.nanoTime();
            org.sqlite.Function.create(connection, "ig_catalog_fold", new org.sqlite.Function() {
                @Override protected void xFunc() throws java.sql.SQLException {
                    String value=value_text(0);
                    result(CatalogText.fold(value));
                }
            }, 1, org.sqlite.Function.FLAG_DETERMINISTIC);
            Throwable original = null;
            try {
                ProgressHandler.setHandler(connection, 1000, new ProgressHandler() {
                    private int calls;
                    @Override protected int progress() {
                        return ++calls >= progressLimit || System.nanoTime() - started > 250_000_000L ? 1 : 0;
                    }
                });
                return operation.apply(connection);
            } catch (Exception | Error failure) {
                original = failure; throw failure;
            } finally {
                cleanup(original, () -> ProgressHandler.clearHandler(connection),
                    () -> org.sqlite.Function.destroy(connection, "ig_catalog_fold", 1));
            }
        });
    }
}
