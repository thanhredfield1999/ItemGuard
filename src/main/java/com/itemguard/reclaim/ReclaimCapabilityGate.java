package com.itemguard.reclaim;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ReclaimCapabilityGate {

    private final List<ItemPresenceProbe> probes;

    public ReclaimCapabilityGate(List<ItemPresenceProbe> probes) {
        this.probes = List.copyOf(Objects.requireNonNull(probes, "probes"));
    }

    public ReclaimDecision evaluate(ReclaimTarget target) {
        Objects.requireNonNull(target, "target");
        if (probes.isEmpty()) {
            return new ReclaimDecision(
                ReclaimDecisionStatus.DENIED_UNAVAILABLE,
                List.of(new PresenceEvidence(
                    "CAPABILITY_GATE",
                    PresenceStatus.UNAVAILABLE,
                    "No presence probes are configured"
                ))
            );
        }

        List<PresenceEvidence> evidence = new ArrayList<>();
        for (ItemPresenceProbe probe : probes) {
            try {
                PresenceEvidence result = Objects.requireNonNull(
                    probe.probe(target),
                    "Presence probe returned null"
                );
                evidence.add(result);
            } catch (RuntimeException | LinkageError failure) {
                evidence.add(new PresenceEvidence(
                    probe.getClass().getName(),
                    PresenceStatus.ERROR,
                    failure.getClass().getSimpleName()
                ));
            }
        }

        if (hasStatus(evidence, PresenceStatus.ERROR)) {
            return orderedDecision(ReclaimDecisionStatus.DENIED_ERROR, evidence, PresenceStatus.ERROR);
        }
        if (hasStatus(evidence, PresenceStatus.PRESENT)) {
            return orderedDecision(ReclaimDecisionStatus.DENIED_PRESENT, evidence, PresenceStatus.PRESENT);
        }
        if (hasStatus(evidence, PresenceStatus.UNAVAILABLE)) {
            return orderedDecision(
                ReclaimDecisionStatus.DENIED_UNAVAILABLE,
                evidence,
                PresenceStatus.UNAVAILABLE
            );
        }
        return new ReclaimDecision(ReclaimDecisionStatus.ELIGIBLE, evidence);
    }

    private boolean hasStatus(List<PresenceEvidence> evidence, PresenceStatus status) {
        return evidence.stream().anyMatch(entry -> entry.status() == status);
    }

    private ReclaimDecision orderedDecision(
        ReclaimDecisionStatus status,
        List<PresenceEvidence> evidence,
        PresenceStatus blockingStatus
    ) {
        List<PresenceEvidence> ordered = new ArrayList<>(evidence.size());
        evidence.stream()
            .filter(entry -> entry.status() == blockingStatus)
            .forEach(ordered::add);
        evidence.stream()
            .filter(entry -> entry.status() != blockingStatus)
            .forEach(ordered::add);
        return new ReclaimDecision(status, ordered);
    }
}
