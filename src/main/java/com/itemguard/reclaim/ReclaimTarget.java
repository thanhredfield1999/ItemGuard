package com.itemguard.reclaim;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record ReclaimTarget(
    UUID playerUuid,
    String code,
    UUID itemUuid
) {
    public ReclaimTarget {
        playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        code = Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT);
        itemUuid = Objects.requireNonNull(itemUuid, "itemUuid");
        if (code.isEmpty()) {
            throw new IllegalArgumentException("Reclaim item code is required");
        }
    }
}
