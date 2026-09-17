package com.itemguard.reclaim;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record ReclaimItemRecord(
    String code,
    UUID itemUuid,
    UUID ownerUuid,
    long lastSeenAt
) {
    public ReclaimItemRecord {
        code = Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT);
        itemUuid = Objects.requireNonNull(itemUuid, "itemUuid");
    }
}
