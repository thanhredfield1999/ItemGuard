package com.itemguard.catalog;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Read-only catalog contract shared by SQLite LITE and Premium MySQL. */
public interface CatalogRepositoryPort {
    CompletableFuture<CatalogPage> find(CatalogQuery query);
    CompletableFuture<List<CatalogEvent>> history(String code, String itemUuid);
    CompletableFuture<CatalogObservations> observations(String code, String itemUuid);
}
