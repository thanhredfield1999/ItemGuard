package com.itemguard.dupe;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScanEpochGeneratorTest {

    @Test
    void epochUsesCurrentTimeForRestartSafeOrdering() {
        ScanEpochGenerator generator = new ScanEpochGenerator(() -> 1_000L);

        assertEquals(1_000L, generator.next());
    }

    @Test
    void epochRemainsStrictlyIncreasingWhenClockDoesNotAdvance() {
        ScanEpochGenerator generator = new ScanEpochGenerator(() -> 1_000L);

        assertEquals(1_000L, generator.next());
        assertEquals(1_001L, generator.next());
        assertEquals(1_002L, generator.next());
    }

    @Test
    void epochRemainsStrictlyIncreasingWhenClockMovesBackward() {
        AtomicLong clock = new AtomicLong(2_000L);
        ScanEpochGenerator generator = new ScanEpochGenerator(clock::get);

        assertEquals(2_000L, generator.next());
        clock.set(1_500L);
        assertEquals(2_001L, generator.next());
    }

    @Test
    void persistedEpochFloorWinsAfterRestartClockRollback() {
        ScanEpochGenerator generator = new ScanEpochGenerator(() -> 1_000L, 5_000L);

        assertEquals(5_001L, generator.next());
        assertEquals(5_002L, generator.next());
    }
}
