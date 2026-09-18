package com.itemguard.dupe;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class ScanEpochGenerator {

    private final LongSupplier clock;
    private final AtomicLong previous = new AtomicLong(Long.MIN_VALUE);

    public ScanEpochGenerator() {
        this(System::currentTimeMillis, Long.MIN_VALUE);
    }

    public ScanEpochGenerator(LongSupplier clock) {
        this(clock, Long.MIN_VALUE);
    }

    public ScanEpochGenerator(LongSupplier clock, long persistedEpochFloor) {
        this.clock = clock;
        this.previous.set(persistedEpochFloor);
    }

    public long next() {
        while (true) {
            long current = previous.get();
            long candidate = Math.max(clock.getAsLong(), current + 1L);
            if (previous.compareAndSet(current, candidate)) {
                return candidate;
            }
        }
    }

    public void initializeEpochFloor(long persistedEpochFloor) {
        previous.accumulateAndGet(persistedEpochFloor, Math::max);
    }
}
