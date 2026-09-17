package com.itemguard.dupe;

import java.util.UUID;

public record DuplicateFinding(
    UUID itemUuid,
    String code,
    long scanEpoch,
    DuplicateStatus status,
    int distinctLocations,
    DuplicateAction action,
    long createdAt,
    String detail
) {}
