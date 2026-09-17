package com.itemguard.catalog;

import com.itemguard.persistence.MySqlConnectionOwner;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** MySQL catalog reader. Search semantics are explicitly backend-specific (M4). */
public final class MySqlCatalogRepository {
    private final MySqlConnectionOwner owner;

    public MySqlCatalogRepository(MySqlConnectionOwner owner) {
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
    }

    /**
     * Uses MySQL's indexed FULLTEXT ngram path when the search text is non-empty. Empty searches
     * remain keyset scans. The UI must label this as MySQL search; it is not SQLite trigram parity.
     */
    public CompletableFuture<CatalogPage> find(CatalogQuery query) {
        java.util.Objects.requireNonNull(query, "query");
        return owner.callAsync(connection -> {
            List<CatalogRow> rows = new ArrayList<>();
            String text = query.text();
            String category = mysqlCategory(query.category());
            String sql = text.isEmpty()
                ? "SELECT code,item_uuid,material,item_name,owner_name,last_location,last_seen_at "
                    + "FROM tracked_items WHERE code > ? AND " + category
                    + " ORDER BY code ASC LIMIT 37"
                : "SELECT code,item_uuid,material,item_name,owner_name,last_location,last_seen_at "
                    + "FROM tracked_items WHERE code > ? AND " + category
                    + " AND MATCH(item_name,material,code) AGAINST (? IN BOOLEAN MODE) "
                    + "ORDER BY code ASC LIMIT 37";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, query.afterCode());
                if (!text.isEmpty()) statement.setString(2, text);
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

    private static String mysqlCategory(CatalogCategory category) {
        return switch (category) {
            case ALL -> "1=1";
            case SWORD -> "material LIKE '%\\_SWORD'";
            case ARMOR -> "(material LIKE '%\\_HELMET' OR material LIKE '%\\_CHESTPLATE' "
                + "OR material LIKE '%\\_LEGGINGS' OR material LIKE '%\\_BOOTS' OR material='ELYTRA')";
            case TOOLS -> "(material LIKE '%\\_AXE' OR material LIKE '%\\_PICKAXE' "
                + "OR material LIKE '%\\_SHOVEL' OR material LIKE '%\\_HOE' "
                + "OR material IN ('SHEARS','FISHING_ROD','FLINT_AND_STEEL'))";
            case RANGED -> "material IN ('BOW','CROSSBOW','TRIDENT') OR material LIKE '%\\_SPEAR'";
            case OTHER -> "NOT (material LIKE '%\\_SWORD' OR material LIKE '%\\_HELMET' "
                + "OR material LIKE '%\\_CHESTPLATE' OR material LIKE '%\\_LEGGINGS' "
                + "OR material LIKE '%\\_BOOTS' OR material='ELYTRA' OR material LIKE '%\\_AXE' "
                + "OR material LIKE '%\\_PICKAXE' OR material LIKE '%\\_SHOVEL' OR material LIKE '%\\_HOE' "
                + "OR material IN ('SHEARS','FISHING_ROD','FLINT_AND_STEEL','BOW','CROSSBOW','TRIDENT') "
                + "OR material LIKE '%\\_SPEAR')";
        };
    }
}
