package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoubleChestSlotPolicyTest {

    private final DoubleChestSlotPolicy policy = new DoubleChestSlotPolicy();

    @Test
    void mapsCombinedSlotsToPhysicalHalfAndLocalSlot() {
        assertEquals(
            new DoubleChestSlotPolicy.ResolvedSlot(
                DoubleChestSlotPolicy.Side.LEFT,
                0
            ),
            policy.resolve(0, 27, 27).orElseThrow()
        );
        assertEquals(
            new DoubleChestSlotPolicy.ResolvedSlot(
                DoubleChestSlotPolicy.Side.LEFT,
                26
            ),
            policy.resolve(26, 27, 27).orElseThrow()
        );
        assertEquals(
            new DoubleChestSlotPolicy.ResolvedSlot(
                DoubleChestSlotPolicy.Side.RIGHT,
                0
            ),
            policy.resolve(27, 27, 27).orElseThrow()
        );
        assertEquals(
            new DoubleChestSlotPolicy.ResolvedSlot(
                DoubleChestSlotPolicy.Side.RIGHT,
                26
            ),
            policy.resolve(53, 27, 27).orElseThrow()
        );
    }

    @Test
    void rejectsNegativeOutOfRangeOrInvalidSideSizes() {
        assertTrue(policy.resolve(-1, 27, 27).isEmpty());
        assertTrue(policy.resolve(54, 27, 27).isEmpty());
        assertTrue(policy.resolve(0, 0, 27).isEmpty());
        assertTrue(policy.resolve(0, 27, 0).isEmpty());
    }
}
