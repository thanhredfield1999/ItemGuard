package com.itemguard.catalog;

/** Recorded physical key, not ownership/current custody evidence. */
public record CatalogObservation(long id, long epoch, boolean epochComplete,
                                 String holderType, String holderId, int slot, long observedAt) {}
