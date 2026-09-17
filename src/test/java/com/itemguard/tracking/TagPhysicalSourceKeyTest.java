package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TagPhysicalSourceKeyTest {

    private static final UUID OWNER = UUID.fromString(
        "11111111-1111-1111-1111-111111111111"
    );
    private static final UUID WORLD = UUID.fromString(
        "22222222-2222-2222-2222-222222222222"
    );

    @Test
    void createsStablePlayerBlockAndEntityKeys() {
        assertEquals(
            "PLAYER_SLOT:" + OWNER + ":8",
            TagPhysicalSourceKey.playerSlot(OWNER, 8)
        );
        assertEquals(
            "BLOCK_CONTAINER_SLOT:" + WORLD + ":-7:-60:10:3",
            TagPhysicalSourceKey.blockContainerSlot(WORLD, -7, -60, 10, 3)
        );
        assertEquals(
            "ENTITY:" + OWNER,
            TagPhysicalSourceKey.entity(OWNER)
        );
    }

    @Test
    void rejectsNegativePhysicalSlot() {
        assertThrows(
            IllegalArgumentException.class,
            () -> TagPhysicalSourceKey.playerSlot(OWNER, -1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TagPhysicalSourceKey.blockContainerSlot(WORLD, 0, 0, 0, -1)
        );
    }
}
