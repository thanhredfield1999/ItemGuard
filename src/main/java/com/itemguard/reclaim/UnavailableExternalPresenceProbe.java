package com.itemguard.reclaim;

import java.util.Objects;

public final class UnavailableExternalPresenceProbe implements ItemPresenceProbe {

    private final String source;
    private final String reason;

    public UnavailableExternalPresenceProbe(String source, String reason) {
        this.source = Objects.requireNonNull(source, "source");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public PresenceEvidence probe(ReclaimTarget target) {
        Objects.requireNonNull(target, "target");
        return new PresenceEvidence(source, PresenceStatus.UNAVAILABLE, reason);
    }
}
