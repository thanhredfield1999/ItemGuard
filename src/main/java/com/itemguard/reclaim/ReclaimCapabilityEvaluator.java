package com.itemguard.reclaim;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class ReclaimCapabilityEvaluator {

    public ReclaimDecision evaluate(
        ReclaimTarget target,
        Supplier<List<ItemPresenceProbe>> probeSupplier
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(probeSupplier, "probeSupplier");
        try {
            List<ItemPresenceProbe> probes = Objects.requireNonNull(
                probeSupplier.get(),
                "Presence probe supplier returned null"
            );
            return new ReclaimCapabilityGate(probes).evaluate(target);
        } catch (RuntimeException | LinkageError failure) {
            return new ReclaimDecision(
                ReclaimDecisionStatus.DENIED_ERROR,
                List.of(new PresenceEvidence(
                    "CAPABILITY_SETUP",
                    PresenceStatus.ERROR,
                    failure.getClass().getSimpleName()
                ))
            );
        }
    }
}
