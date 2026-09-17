package com.itemguard.scan;

import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public final class BoundedSlotMutator<T> {

    public int mutate(
        int slotCount,
        int mutationBudget,
        IntFunction<T> reader,
        Predicate<T> shouldMutate,
        UnaryOperator<T> transformer,
        BiConsumer<Integer, T> writer
    ) {
        if (slotCount <= 0 || mutationBudget <= 0) {
            return 0;
        }

        int changed = 0;
        for (int slot = 0; slot < slotCount && changed < mutationBudget; slot++) {
            T current = reader.apply(slot);
            if (!shouldMutate.test(current)) {
                continue;
            }

            T transformed = transformer.apply(current);
            if (transformed == null) {
                continue;
            }
            writer.accept(slot, transformed);
            changed++;
        }
        return changed;
    }
}
