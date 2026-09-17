package com.itemguard.reclaim;

import java.util.Optional;

@FunctionalInterface
public interface ReclaimItemLookup {
    Optional<ReclaimItemRecord> find(String code);
}
