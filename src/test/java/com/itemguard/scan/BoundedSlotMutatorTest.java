package com.itemguard.scan;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundedSlotMutatorTest {

    private final BoundedSlotMutator<String> mutator = new BoundedSlotMutator<>();

    @Test
    void writesTransformedValueBackToOriginalSlot() {
        String[] slots = new String[9];
        slots[0] = "main-hand";
        slots[5] = "target";

        int changed = mutator.mutate(
            slots.length,
            10,
            index -> slots[index],
            "target"::equals,
            value -> "tagged-" + value,
            (index, value) -> slots[index] = value
        );

        assertEquals(1, changed);
        assertEquals("main-hand", slots[0]);
        assertEquals("tagged-target", slots[5]);
    }

    @Test
    void stopsAfterMutationBudgetIsExhausted() {
        String[] slots = {"target-a", "target-b", "untouched"};

        int changed = mutator.mutate(
            slots.length,
            1,
            index -> slots[index],
            value -> value.startsWith("target"),
            value -> "tagged-" + value,
            (index, value) -> slots[index] = value
        );

        assertEquals(1, changed);
        assertArrayEquals(new String[]{"tagged-target-a", "target-b", "untouched"}, slots);
    }

    @Test
    void zeroBudgetDoesNotInvokeMutation() {
        String[] slots = {"target"};
        String[] original = Arrays.copyOf(slots, slots.length);

        int changed = mutator.mutate(
            slots.length,
            0,
            index -> slots[index],
            value -> true,
            value -> "tagged",
            (index, value) -> slots[index] = value
        );

        assertEquals(0, changed);
        assertArrayEquals(original, slots);
    }
}
