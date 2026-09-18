package com.itemguard.reclaim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalPresenceProbeFactoryTest {

    private final ReclaimTarget target = new ReclaimTarget(
        UUID.randomUUID(),
        "AB12CD",
        UUID.randomUUID()
    );

    @Test
    void absentPluginsRemainNamedUnavailableCapabilities() {
        ExternalPresenceProbeFactory factory = new ExternalPresenceProbeFactory(
            pluginName -> Optional.empty()
        );

        PresenceEvidence vaults = factory.playerVaultsProbe().probe(target);
        PresenceEvidence auction = factory.zAuctionHouseProbe().probe(target);

        assertEquals("PLAYER_VAULTS", vaults.source());
        assertEquals(PresenceStatus.UNAVAILABLE, vaults.status());
        assertTrue(vaults.detail().contains("not installed"),
            "the wording now distinguishes 'not here' from 'here and unreadable', which is what "
                + "INSTALLED_ONLY keys off: " + vaults.detail());
        assertTrue(vaults.detail().contains("absence cannot be proven"));
        assertEquals("ZAUCTIONHOUSE", auction.source());
        assertEquals(PresenceStatus.UNAVAILABLE, auction.status());
        assertTrue(auction.detail().contains("not installed"), auction.detail());
        assertTrue(auction.detail().contains("absence cannot be proven"));
    }

    @Test
    void detectedPluginVersionDoesNotPretendToProveAbsence() {
        Map<String, String> versions = Map.of(
            "PlayerVaultsX", "1.0.0",
            "zAuctionHouse", "4.0.1.2"
        );
        ExternalPresenceProbeFactory factory = new ExternalPresenceProbeFactory(
            pluginName -> Optional.ofNullable(versions.get(pluginName))
        );

        PresenceEvidence vaults = factory.playerVaultsProbe().probe(target);
        PresenceEvidence auction = factory.zAuctionHouseProbe().probe(target);

        assertEquals(PresenceStatus.UNAVAILABLE, vaults.status());
        assertTrue(vaults.detail().contains("PlayerVaultsX 1.0.0"));
        assertTrue(vaults.detail().contains("bounded read-only"));
        assertEquals(PresenceStatus.UNAVAILABLE, auction.status());
        assertTrue(auction.detail().contains("zAuctionHouse 4.0.1.2"));
        assertTrue(auction.detail().contains("bounded read-only"));
    }

    @Test
    void legacyPlayerVaultsPluginNameIsReportedExactly() {
        ExternalPresenceProbeFactory factory = new ExternalPresenceProbeFactory(
            pluginName -> "PlayerVaults".equals(pluginName)
                ? Optional.of("4.4.14")
                : Optional.empty()
        );

        PresenceEvidence vaults = factory.playerVaultsProbe().probe(target);

        assertEquals(PresenceStatus.UNAVAILABLE, vaults.status());
        assertTrue(vaults.detail().contains("PlayerVaults 4.4.14"));
        assertTrue(vaults.detail().contains("public API"));
        assertTrue(vaults.detail().contains("synchronous file I/O"));
        assertTrue(vaults.detail().contains("no hard scan bound"));
    }

    @Test
    void separateModrinthPlayerVaultsXForkReportsMissingEnumerationApi() {
        ExternalPresenceProbeFactory factory = new ExternalPresenceProbeFactory(
            pluginName -> "PlayerVaultsX".equals(pluginName)
                ? Optional.of("1.0.1")
                : Optional.empty()
        );

        PresenceEvidence vaults = factory.playerVaultsProbe().probe(target);

        assertEquals(PresenceStatus.UNAVAILABLE, vaults.status());
        assertTrue(vaults.detail().contains("PlayerVaultsX 1.0.1"));
        assertTrue(vaults.detail().contains("separate fork"));
        assertTrue(vaults.detail().contains("enumerate"));
    }

    @Test
    void zAuctionHouseV4ReportsPublicButSynchronousFullBucketContract() {
        ExternalPresenceProbeFactory factory = new ExternalPresenceProbeFactory(
            pluginName -> "zAuctionHouse".equals(pluginName)
                ? Optional.of("4.0.1.2")
                : Optional.empty()
        );

        PresenceEvidence auction = factory.zAuctionHouseProbe().probe(target);

        assertEquals(PresenceStatus.UNAVAILABLE, auction.status());
        assertTrue(auction.detail().contains("public API"));
        assertTrue(auction.detail().contains("synchronous full-bucket"));
        assertTrue(auction.detail().contains("listed, purchased, and expired"));
    }

    @Test
    void zAuctionHouseV3ReportsPublicButUnboundedSynchronousFullListContract() {
        ExternalPresenceProbeFactory factory = new ExternalPresenceProbeFactory(
            pluginName -> "zAuctionHouse".equals(pluginName)
                ? Optional.of("3.2.1.9")
                : Optional.empty()
        );

        PresenceEvidence auction = factory.zAuctionHouseProbe().probe(target);

        assertEquals(PresenceStatus.UNAVAILABLE, auction.status());
        assertTrue(auction.detail().contains("zAuctionHouse 3.2.1.9"));
        assertTrue(auction.detail().contains("public API"));
        assertTrue(auction.detail().contains("synchronous full-list"));
        assertTrue(auction.detail().contains("no hard bound"));
        assertTrue(auction.detail().contains("cross-storage read consistency"));
    }

    @Test
    void pluginVersionLookupFailureBecomesCapabilitySetupError() {
        ReclaimDecision decision = new ReclaimCapabilityEvaluator().evaluate(target, () -> {
            ExternalPresenceProbeFactory factory = new ExternalPresenceProbeFactory(
                pluginName -> {
                    throw new IllegalStateException("plugin-manager-failure");
                }
            );
            return List.of(factory.playerVaultsProbe());
        });

        assertEquals(ReclaimDecisionStatus.DENIED_ERROR, decision.status());
        PresenceEvidence blocker = decision.blockingEvidence().orElseThrow();
        assertEquals("CAPABILITY_SETUP", blocker.source());
        assertEquals(PresenceStatus.ERROR, blocker.status());
        assertTrue(blocker.detail().contains("IllegalStateException"));
    }

    @Test
    void nullPluginVersionLookupBecomesCapabilitySetupError() {
        ReclaimDecision decision = new ReclaimCapabilityEvaluator().evaluate(target, () -> {
            ExternalPresenceProbeFactory factory = new ExternalPresenceProbeFactory(
                pluginName -> null
            );
            return List.of(factory.zAuctionHouseProbe());
        });

        assertEquals(ReclaimDecisionStatus.DENIED_ERROR, decision.status());
        PresenceEvidence blocker = decision.blockingEvidence().orElseThrow();
        assertEquals("CAPABILITY_SETUP", blocker.source());
        assertEquals(PresenceStatus.ERROR, blocker.status());
        assertTrue(blocker.detail().contains("NullPointerException"));
    }
}
