package com.itemguard.reclaim;

public enum ReclaimPreflightStatus {
    ELIGIBLE,
    NOT_TRACKED,
    NOT_OWNER,
    SNAPSHOT_MISSING,
    SNAPSHOT_INVALID,
    DENIED_CAPABILITY
}
