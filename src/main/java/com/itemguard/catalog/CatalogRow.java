package com.itemguard.catalog;

/** Scalar read model only: legacy owner is not authoritative physical custody. */
public record CatalogRow(String code, String itemUuid, String material, String name,
                         String ownerName, String location, long lastSeenAt) {}
