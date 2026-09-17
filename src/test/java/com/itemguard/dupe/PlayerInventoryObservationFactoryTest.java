package com.itemguard.dupe;

import com.itemguard.identity.IdentityTagResolution;
import com.itemguard.identity.IdentityTagStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerInventoryObservationFactoryTest {

    private final PlayerInventoryObservationFactory factory =
        new PlayerInventoryObservationFactory();

    @Test
    void completeIdentityProducesExactPlayerSlotObservation() {
        UUID itemUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        ItemObservation observation = factory.create(
            new IdentityTagResolution(IdentityTagStatus.COMPLETE, "AB12CD", itemUuid),
            42L,
            playerUuid,
            40,
            1_000L
        ).orElseThrow();

        assertEquals(itemUuid, observation.itemUuid());
        assertEquals("AB12CD", observation.code());
        assertEquals(42L, observation.scanEpoch());
        assertEquals(
            new ObservationKey(HolderType.PLAYER, playerUuid.toString(), 40),
            observation.key()
        );
        assertEquals(1_000L, observation.observedAt());
    }

    @Test
    void absentOrCorruptIdentityDoesNotProduceObservation() {
        UUID playerUuid = UUID.randomUUID();

        assertTrue(factory.create(
            new IdentityTagResolution(IdentityTagStatus.ABSENT, null, null),
            42L,
            playerUuid,
            5,
            1_000L
        ).isEmpty());
        assertTrue(factory.create(
            new IdentityTagResolution(IdentityTagStatus.CORRUPT, null, null),
            42L,
            playerUuid,
            5,
            1_000L
        ).isEmpty());
    }

    @Test
    void invalidSlotIsRejected() {
        assertTrue(factory.create(
            new IdentityTagResolution(
                IdentityTagStatus.COMPLETE,
                "AB12CD",
                UUID.randomUUID()
            ),
            42L,
            UUID.randomUUID(),
            -1,
            1_000L
        ).isEmpty());
    }
}
