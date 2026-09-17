package com.itemguard.catalog;

public record CatalogEvent(long id, String action, String actor, String actorUuid,
                           String location, long timestamp, String detail) {}
