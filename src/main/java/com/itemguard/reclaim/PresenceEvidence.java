package com.itemguard.reclaim;

import java.util.Objects;

public record PresenceEvidence(
    String source,
    PresenceStatus status,
    String detail
) {
    public PresenceEvidence {
        source = Objects.requireNonNull(source, "source").trim();
        status = Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail.trim();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("Presence evidence source is required");
        }
    }
}
