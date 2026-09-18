package com.itemguard.catalog;

import com.itemguard.persistence.JdbcConnectionOwner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** MySQL catalog reader. Search semantics are explicitly backend-specific (M4). */
public final class MySqlCatalogRepository implements CatalogRepositoryPort {
    private final JdbcConnectionOwner owner;

    public MySqlCatalogRepository(JdbcConnectionOwner owner) {
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
    }

    /**
     * Uses MySQL's indexed FULLTEXT ngram path for non-empty text. Empty searches remain keyset
     * scans. This is intentionally not claimed equivalent to SQLite FTS5/trigram.
     */
    @Override
    public CompletableFuture<CatalogPage> find(CatalogQuery query) {
        java.util.Objects.requireNonNull(query, "query");
        return owner.callAsync(connection -> {
            List<CatalogRow> rows = new ArrayList<>();
            String text = query.text();
            String category = mysqlCategory(query.category());
            String sql = text.isEmpty()
                ? "SELECT code,item_uuid,material,item_name,owner_name,last_location,last_seen_at "
                    + "FROM tracked_items WHERE code > ? AND " + category
                    + " AND (? = '' OR owner_name = ?) ORDER BY code ASC LIMIT 37"
                : "SELECT code,item_uuid,material,item_name,owner_name,last_location,last_seen_at "
                    + "FROM tracked_items WHERE code > ? AND " + category
                    + " AND (? = '' OR owner_name = ?)"
                    + " AND MATCH(item_name,material,code) AGAINST (? IN BOOLEAN MODE)"
                    + " ORDER BY code ASC LIMIT 37";
            try (var statement = connection.prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, query.afterCode());
                statement.setString(index++, query.ownerName());
                statement.setString(index++, query.ownerName());
                if (!text.isEmpty()) statement.setString(index, text);
                try (var result = statement.executeQuery()) {
                    while (result.next()) rows.add(new CatalogRow(result.getString(1), result.getString(2),
                        result.getString(3), result.getString(4), result.getString(5), result.getString(6),
                        result.getLong(7)));
                }
            }
            boolean more = rows.size() > 36;
            if (more) rows.remove(rows.size() - 1);
            return new CatalogPage(rows, more);
        });
    }

    @Override
    public CompletableFuture<List<CatalogEvent>> history(String code, String itemUuid) {
        java.util.Objects.requireNonNull(code, "code");
        java.util.Objects.requireNonNull(itemUuid, "itemUuid");
        return owner.callAsync(connection -> {
            List<CatalogEvent> events = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                SELECT id,action,player_name,player_uuid,location,timestamp,additional_data
                FROM item_history WHERE code = ? AND item_uuid = ?
                ORDER BY timestamp DESC, id DESC LIMIT 100
                """)) {
                statement.setString(1, code);
                statement.setString(2, itemUuid);
                try (var result = statement.executeQuery()) {
                    while (result.next()) events.add(new CatalogEvent(result.getLong(1), result.getString(2),
                        result.getString(3), result.getString(4), result.getString(5), result.getLong(6),
                        result.getString(7)));
                }
            }
            return List.copyOf(events);
        });
    }

    @Override
    public CompletableFuture<CatalogObservations> observations(String code, String itemUuid) {
        java.util.Objects.requireNonNull(code, "code");
        java.util.Objects.requireNonNull(itemUuid, "itemUuid");
        return owner.callAsync(connection -> {
            List<CatalogObservation> rows = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                SELECT observation_id,scan_epoch,epoch_complete,holder_type,holder_id,slot,observed_at
                FROM item_observations WHERE item_uuid = ? AND code = ?
                ORDER BY scan_epoch DESC, observation_id DESC LIMIT 101
                """)) {
                statement.setString(1, itemUuid);
                statement.setString(2, code);
                try (var result = statement.executeQuery()) {
                    while (result.next()) rows.add(new CatalogObservation(result.getLong(1), result.getLong(2),
                        result.getInt(3) == 1, bounded(result.getString(4), 64),
                        bounded(result.getString(5), 255), result.getInt(6), result.getLong(7)));
                }
            }
            boolean more = rows.size() > 100;
            if (more) rows.remove(rows.size() - 1);
            return new CatalogObservations(rows, more);
        });
    }

    private static String bounded(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max) + "… [rút gọn]";
    }

    private static String mysqlCategory(CatalogCategory category) {
        return switch (category) {
            case ALL -> "1=1";
            case SWORD -> "material LIKE '%\\_SWORD'";
            case ARMOR -> "(material LIKE '%\\_HELMET' OR material LIKE '%\\_CHESTPLATE' "
                + "OR material LIKE '%\\_LEGGINGS' OR material LIKE '%\\_BOOTS' OR material='ELYTRA')";
            case TOOLS -> "(material LIKE '%\\_AXE' OR material LIKE '%\\_PICKAXE' "
                + "OR material LIKE '%\\_SHOVEL' OR material LIKE '%\\_HOE' "
                + "OR material IN ('SHEARS','FISHING_ROD','FLINT_AND_STEEL'))";
            case RANGED -> "(material IN ('BOW','CROSSBOW','TRIDENT') OR material LIKE '%\\_SPEAR')";
            case OTHER -> "NOT (material LIKE '%\\_SWORD' OR material LIKE '%\\_HELMET' "
                + "OR material LIKE '%\\_CHESTPLATE' OR material LIKE '%\\_LEGGINGS' "
                + "OR material LIKE '%\\_BOOTS' OR material='ELYTRA' OR material LIKE '%\\_AXE' "
                + "OR material LIKE '%\\_PICKAXE' OR material LIKE '%\\_SHOVEL' OR material LIKE '%\\_HOE' "
                + "OR material IN ('SHEARS','FISHING_ROD','FLINT_AND_STEEL','BOW','CROSSBOW','TRIDENT') "
                + "OR material LIKE '%\\_SPEAR')";
        };
    }
}
