package com.itemguard.search;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record ItemSearchRequest(
    String code,
    ItemSearchMode mode,
    ItemSearchState state,
    UUID actorUuid,
    String actorName,
    long createdAt,
    long expiresAt,
    long updatedAt
) {
    public ItemSearchRequest {
        code = Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT);
        mode = Objects.requireNonNull(mode, "mode");
        state = Objects.requireNonNull(state, "state");
        actorName = Objects.requireNonNull(actorName, "actorName").trim();
        if (code.isEmpty() || actorName.isEmpty()) {
            throw new IllegalArgumentException("Search code and actor name are required");
        }
        if (expiresAt <= createdAt) {
            throw new IllegalArgumentException("Search expiry must be after creation time");
        }
        if (updatedAt < createdAt) {
            throw new IllegalArgumentException("Search update time cannot precede creation time");
        }
    }
}
