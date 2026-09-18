package com.itemguard.reclaim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The difference between "that storage plugin is not here" and "that storage plugin is here and I
 * cannot read it".
 *
 * <p>Both used to be {@code UNAVAILABLE}, which meant denial — correct for the second and fatal for
 * the first: a server without PlayerVaults or zAuctionHouse could prove absence in the player's own
 * inventory and ender chest (the only places its items can be) and still be refused forever, so the
 * reclaim feature could never fire on a normal server. These tests pin the two apart and pin that the
 * refusing side is still the default.
 */
class ExternalAbsenceModeTest {

    private final ReclaimTarget target = new ReclaimTarget(
        UUID.randomUUID(),
        "AB12CD",
        UUID.randomUUID()
    );

    private ExternalPresenceProbeFactory factoryWithNothingInstalled() {
        return new ExternalPresenceProbeFactory(name -> Optional.empty());
    }

    @Test
    void strictRefusesWhenNothingCanBeProven() {
        ExternalPresenceProbeFactory factory = factoryWithNothingInstalled();

        PresenceEvidence vaults = factory.playerVaultsProbe(ExternalAbsenceMode.STRICT).probe(target);
        PresenceEvidence auction = factory.zAuctionHouseProbe(ExternalAbsenceMode.STRICT).probe(target);

        assertEquals(PresenceStatus.UNAVAILABLE, vaults.status(), "STRICT must refuse, not assume");
        assertEquals(PresenceStatus.UNAVAILABLE, auction.status());
        assertTrue(vaults.detail().contains("absence cannot be proven"), vaults.detail());
    }

    @Test
    void strictIsWhatTheNoArgumentMethodsStillDo() {
        ExternalPresenceProbeFactory factory = factoryWithNothingInstalled();

        assertEquals(
            PresenceStatus.UNAVAILABLE,
            factory.playerVaultsProbe().probe(target).status(),
            "the old signature must keep the old, refusing behaviour: existing callers must not "
                + "silently become permitted to issue"
        );
        assertEquals(PresenceStatus.UNAVAILABLE, factory.zAuctionHouseProbe().probe(target).status());
    }

    @Test
    void installedOnlySkipsAPluginThatIsNotThereAndSaysSo() {
        ExternalPresenceProbeFactory factory = factoryWithNothingInstalled();

        PresenceEvidence vaults = factory.playerVaultsProbe(ExternalAbsenceMode.INSTALLED_ONLY)
            .probe(target);
        PresenceEvidence auction = factory.zAuctionHouseProbe(ExternalAbsenceMode.INSTALLED_ONLY)
            .probe(target);

        assertEquals(PresenceStatus.NOT_APPLICABLE, vaults.status());
        assertEquals(PresenceStatus.NOT_APPLICABLE, auction.status());
        assertTrue(vaults.detail().contains("not installed"), vaults.detail());
        assertTrue(vaults.detail().contains("INSTALLED_ONLY"),
            "the evidence must say which mode produced the skip: " + vaults.detail());
    }

    @Test
    void anInstalledButUnreadablePluginDeniesInBothModes() {
        ExternalPresenceProbeFactory factory = new ExternalPresenceProbeFactory(
            name -> Optional.of("4.4.5")
        );

        for (ExternalAbsenceMode mode : ExternalAbsenceMode.values()) {
            PresenceEvidence vaults = factory.playerVaultsProbe(mode).probe(target);
            assertEquals(PresenceStatus.UNAVAILABLE, vaults.status(),
                "an installed plugin whose contents cannot be read must deny in " + mode);
        }
    }

    @Test
    void anUnrecognisedOrBlankSettingRefusesRatherThanPermits() {
        assertEquals(ExternalAbsenceMode.INSTALLED_ONLY, ExternalAbsenceMode.parse("INSTALLED_ONLY"));
        assertEquals(ExternalAbsenceMode.INSTALLED_ONLY, ExternalAbsenceMode.parse("installed_only"),
            "the key is matched case-insensitively");
        assertEquals(ExternalAbsenceMode.STRICT, ExternalAbsenceMode.parse("nonsense"));
        assertEquals(ExternalAbsenceMode.STRICT, ExternalAbsenceMode.parse(""));
        assertEquals(ExternalAbsenceMode.STRICT, ExternalAbsenceMode.parse(null));
    }

    @Test
    void theGateTreatsASkippedSourceAsNonBlockingAndKeepsItInTheEvidence() {
        ReclaimCapabilityGate gate = new ReclaimCapabilityGate(List.of(
            ignored -> new PresenceEvidence("PLAYER_INVENTORY", PresenceStatus.ABSENT, "not in inventory"),
            new SkippedExternalProbe("PLAYER_VAULTS", "not installed (INSTALLED_ONLY)"),
            new SkippedExternalProbe("ZAUCTIONHOUSE", "not installed (INSTALLED_ONLY)")
        ));

        ReclaimDecision decision = gate.evaluate(target);

        assertEquals(ReclaimDecisionStatus.ELIGIBLE, decision.status(),
            "skipped sources must not block an otherwise provable reclaim");
        assertEquals(3, decision.evidence().size(),
            "and they must stay in the evidence so the claim detail names what was skipped");
    }

    @Test
    void aSkippedSourceCannotMaskAPresentOne() {
        ReclaimCapabilityGate gate = new ReclaimCapabilityGate(List.of(
            new SkippedExternalProbe("PLAYER_VAULTS", "not installed (INSTALLED_ONLY)"),
            ignored -> new PresenceEvidence("PLAYER_INVENTORY", PresenceStatus.PRESENT, "slot 9")
        ));

        ReclaimDecision decision = gate.evaluate(target);

        assertEquals(ReclaimDecisionStatus.DENIED_PRESENT, decision.status(),
            "the item is in the player's own inventory; skipping a storage plugin cannot help here");
    }
}
