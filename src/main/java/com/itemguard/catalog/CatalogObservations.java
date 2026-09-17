package com.itemguard.catalog;

import java.util.List;

public record CatalogObservations(List<CatalogObservation> rows, boolean hasMore) {
    public CatalogObservations { rows = List.copyOf(rows); }
}
