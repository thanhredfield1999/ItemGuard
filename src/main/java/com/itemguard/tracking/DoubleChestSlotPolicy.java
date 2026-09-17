package com.itemguard.tracking;

import java.util.Optional;

public final class DoubleChestSlotPolicy {

    public Optional<ResolvedSlot> resolve(int rawSlot, int leftSize, int rightSize) {
        if (rawSlot < 0 || leftSize <= 0 || rightSize <= 0) {
            return Optional.empty();
        }
        if (rawSlot < leftSize) {
            return Optional.of(new ResolvedSlot(Side.LEFT, rawSlot));
        }
        int rightSlot = rawSlot - leftSize;
        if (rightSlot >= rightSize) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedSlot(Side.RIGHT, rightSlot));
    }

    public enum Side {
        LEFT,
        RIGHT
    }

    public record ResolvedSlot(Side side, int localSlot) {}
}
