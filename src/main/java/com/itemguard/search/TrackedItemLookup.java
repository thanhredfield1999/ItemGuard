package com.itemguard.search;

@FunctionalInterface
public interface TrackedItemLookup {
    boolean exists(String code);
}
