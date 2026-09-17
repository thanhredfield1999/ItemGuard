package com.itemguard.dupe;

import com.itemguard.identity.IdentityTagResolution;
import com.itemguard.identity.IdentityTagStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerInventoryObservationFactoryTest {

    private final ContainerInventoryObservationFactory factory =
        new ContainerInventoryObservationFactory();

    @Test
    void completeIdentityProducesStableBlockContainerSlotObservation() {
        UUID itemUuid = UUID.randomUUID();
        UUID worldUuid = UUID.randomUUID();

        ItemObservation observation = factory.create(
            new IdentityTagResolution(IdentityTagStatus.COMPLETE, "AB12CD", itemUuid),
            42L,
            worldUuid,
            10,
            64,
            -7,
            3,
            1_000L
        ).orElseThrow();

        assertEquals(itemUuid, observation.itemUuid());
        assertEquals("AB12CD", observation.code());
        assertEquals(42L, observation.scanEpoch());
        assertEquals(
            new ObservationKey(
                HolderType.CONTAINER,
                "BLOCK:" + worldUuid + ":10:64:-7",
                3
            ),
            observation.key()
        );
        assertEquals(1_000L, observation.observedAt());
    }

    @Test
    void absentCorruptOrInvalidSlotDoesNotProduceObservation() {
        UUID worldUuid = UUID.randomUUID();

        assertTrue(factory.create(
            new IdentityTagResolution(IdentityTagStatus.ABSENT, null, null),
            42L, worldUuid, 1, 2, 3, 0, 1_000L
        ).isEmpty());
        assertTrue(factory.create(
            new IdentityTagResolution(IdentityTagStatus.CORRUPT, null, null),
            42L, worldUuid, 1, 2, 3, 0, 1_000L
        ).isEmpty());
        assertTrue(factory.create(
            new IdentityTagResolution(
                IdentityTagStatus.COMPLETE,
                "AB12CD",
                UUID.randomUUID()
            ),
            42L, worldUuid, 1, 2, 3, -1, 1_000L
        ).isEmpty());
    }
}
