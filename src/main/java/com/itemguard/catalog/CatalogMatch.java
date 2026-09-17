package com.itemguard.catalog;

import java.util.Optional;

/** Literal MATCH encoding only; does not establish index readiness. */
final class CatalogMatch {
    private CatalogMatch() {}

    static Optional<String> literal(String input) {
        new CatalogQuery(input, "", CatalogCategory.ALL, "");
        String folded = CatalogText.fold(input);
        if (folded.codePointCount(0, folded.length()) < 3) return Optional.empty();
        return Optional.of("\"" + folded.replace("\"", "\"\"") + "\"");
    }
}
