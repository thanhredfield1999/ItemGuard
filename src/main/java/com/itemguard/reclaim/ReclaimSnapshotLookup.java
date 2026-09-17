package com.itemguard.reclaim;

import com.itemguard.snapshot.ItemSnapshot;

import java.util.Optional;

@FunctionalInterface
public interface ReclaimSnapshotLookup {
    Optional<ItemSnapshot> find(String code);
}
