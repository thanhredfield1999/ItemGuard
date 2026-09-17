package com.itemguard.catalog;

import java.util.List;

public record CatalogPage(List<CatalogRow> items, boolean hasMore) {
    public CatalogPage { items = List.copyOf(items); }
}
