package com.itemguard.reclaim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReclaimCapabilityGateTest {

    private final ReclaimTarget target = new ReclaimTarget(
        UUID.randomUUID(),
        "AB12CD",
        UUID.randomUUID()
    );

    @Test
    void allowsOnlyWhenEveryRequiredCapabilityProvesAbsence() {
        ReclaimCapabilityGate gate = new ReclaimCapabilityGate(List.of(
            probe("PLAYER_INVENTORY", PresenceStatus.ABSENT),
            probe("PLAYER_VAULTS", PresenceStatus.ABSENT),
            probe("ZAUCTIONHOUSE", PresenceStatus.ABSENT)
        ));

        ReclaimDecision decision = gate.evaluate(target);

        assertEquals(ReclaimDecisionStatus.ELIGIBLE, decision.status());
        assertEquals(3, decision.evidence().size());
    }

    @Test
    void deniesWhenAnyCapabilityFindsTheIdentity() {
        ReclaimCapabilityGate gate = new ReclaimCapabilityGate(List.of(
            probe("PLAYER_INVENTORY", PresenceStatus.ABSENT),
            probe("PLAYER_VAULTS", PresenceStatus.PRESENT),
            probe("ZAUCTIONHOUSE", PresenceStatus.ABSENT)
        ));

        ReclaimDecision decision = gate.evaluate(target);

        assertEquals(ReclaimDecisionStatus.DENIED_PRESENT, decision.status());
        assertEquals("PLAYER_VAULTS", decision.blockingEvidence().orElseThrow().source());
    }

    @Test
    void unavailableOrErrorCapabilityFailsClosed() {
        ReclaimDecision unavailable = new ReclaimCapabilityGate(List.of(
            probe("PLAYER_INVENTORY", PresenceStatus.ABSENT),
            probe("PLAYER_VAULTS", PresenceStatus.UNAVAILABLE)
        )).evaluate(target);
        ReclaimDecision error = new ReclaimCapabilityGate(List.of(
            probe("PLAYER_INVENTORY", PresenceStatus.ERROR)
        )).evaluate(target);

        assertEquals(ReclaimDecisionStatus.DENIED_UNAVAILABLE, unavailable.status());
        assertEquals(ReclaimDecisionStatus.DENIED_ERROR, error.status());
    }

    @Test
    void missingCapabilitySetFailsClosed() {
        ReclaimDecision decision = new ReclaimCapabilityGate(List.of()).evaluate(target);

        assertEquals(ReclaimDecisionStatus.DENIED_UNAVAILABLE, decision.status());
    }

    @Test
    void unverifiedExternalAdapterReportsNamedUnavailableEvidence() {
        PresenceEvidence evidence = new UnavailableExternalPresenceProbe(
            "PLAYER_VAULTS",
            "Public API version is not verified"
        ).probe(target);

        assertEquals("PLAYER_VAULTS", evidence.source());
        assertEquals(PresenceStatus.UNAVAILABLE, evidence.status());
        assertEquals("Public API version is not verified", evidence.detail());
    }

    @Test
    void linkageFailureTakesPriorityOverPresentEvidenceForAuditClassification() {
        ReclaimDecision decision = new ReclaimCapabilityGate(List.of(
            probe("PLAYER_INVENTORY", PresenceStatus.PRESENT),
            requested -> {
                throw new NoClassDefFoundError("optional-adapter");
            }
        )).evaluate(target);

        assertEquals(ReclaimDecisionStatus.DENIED_ERROR, decision.status());
        assertEquals(PresenceStatus.ERROR, decision.blockingEvidence().orElseThrow().status());
    }

    @Test
    void probeFailureTakesPriorityOverPresentEvidenceForAuditClassification() {
        ReclaimDecision decision = new ReclaimCapabilityGate(List.of(
            probe("PLAYER_INVENTORY", PresenceStatus.PRESENT),
            requested -> {
                throw new IllegalStateException("optional-adapter");
            }
        )).evaluate(target);

        assertEquals(ReclaimDecisionStatus.DENIED_ERROR, decision.status());
        assertEquals(PresenceStatus.ERROR, decision.blockingEvidence().orElseThrow().status());
    }

    @Test
    void linkageFailureWhileBuildingProbesFailsClosed() {
        ReclaimDecision decision = new ReclaimCapabilityEvaluator().evaluate(
            target,
            () -> {
                throw new NoClassDefFoundError("optional-adapter");
            }
        );

        assertEquals(ReclaimDecisionStatus.DENIED_ERROR, decision.status());
        PresenceEvidence blocker = decision.blockingEvidence().orElseThrow();
        assertEquals("CAPABILITY_SETUP", blocker.source());
        assertEquals(PresenceStatus.ERROR, blocker.status());
        assertTrue(blocker.detail().contains("NoClassDefFoundError"));
    }

    private ItemPresenceProbe probe(String source, PresenceStatus status) {
        return requested -> new PresenceEvidence(source, status, status.name());
    }
}
