package com.itemguard.catalog;

/** Text folding shared by catalog search implementations. */
public final class CatalogText {
    private CatalogText() {}

    /** Registers a dedicated function for this connection's lifetime. */
    public static void registerIndexFunction(java.sql.Connection connection) throws java.sql.SQLException {
        org.sqlite.Function.create(connection, "ig_catalog_index_fold_v1", new org.sqlite.Function() {
            @Override protected void xFunc() throws java.sql.SQLException {
                String value = value_text(0);
                result(value == null ? "" : fold(value));
            }
        }, 1, org.sqlite.Function.FLAG_DETERMINISTIC);
    }

    static String fold(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
            .toLowerCase(java.util.Locale.ROOT);
    }
}
