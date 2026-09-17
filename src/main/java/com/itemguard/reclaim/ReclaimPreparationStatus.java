package com.itemguard.reclaim;

public enum ReclaimPreparationStatus {
    RESERVED,
    NOT_TRACKED,
    NOT_OWNER,
    SNAPSHOT_MISSING,
    SNAPSHOT_INVALID,
    CLAIM_CONFLICT
}
