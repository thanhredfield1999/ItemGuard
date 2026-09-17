package com.itemguard.reclaim;

import java.util.List;
import java.util.Optional;

public record ReclaimDecision(
    ReclaimDecisionStatus status,
    List<PresenceEvidence> evidence
) {
    public ReclaimDecision {
        evidence = List.copyOf(evidence);
    }

    public Optional<PresenceEvidence> blockingEvidence() {
        return evidence.stream()
            .filter(entry -> entry.status() != PresenceStatus.ABSENT)
            .findFirst();
    }
}
