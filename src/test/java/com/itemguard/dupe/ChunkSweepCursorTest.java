package com.itemguard.dupe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSweepCursorTest {

    @Test
    void constructorRejectsZeroChunksPerTickBudget() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkSweepCursor<String>(0));
    }

    @Test
    void constructorRejectsNegativeChunksPerTickBudget() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkSweepCursor<String>(-1));
    }

    @Test
    void emptyChunkListCompletesImmediatelyAsCompletePass() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(2);

        assertTrue(cursor.startPass(List.of(), 1L));
        ChunkSweepCursor.SweepBatch<String> batch = cursor.advance(handle -> true);

        assertTrue(batch.passComplete());
        assertEquals(List.of(), batch.visited());
        assertEquals(1L, batch.epochId());
    }

    @Test
    void advanceVisitsAtMostBudgetChunksPerCall() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(2);
        cursor.startPass(List.of("a", "b", "c", "d", "e"), 7L);

        ChunkSweepCursor.SweepBatch<String> firstBatch = cursor.advance(handle -> true);

        assertEquals(List.of("a", "b"), firstBatch.visited());
        assertFalse(firstBatch.passComplete());
    }

    @Test
    void successiveAdvanceCallsVisitEveryCapturedChunkExactlyOnceInOrder() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(2);
        List<String> chunks = List.of("a", "b", "c", "d", "e");
        cursor.startPass(chunks, 3L);

        List<String> visitedInOrder = new ArrayList<>();
        ChunkSweepCursor.SweepBatch<String> batch;
        do {
            batch = cursor.advance(handle -> true);
            visitedInOrder.addAll(batch.visited());
        } while (!batch.passComplete());

        assertEquals(chunks, visitedInOrder);
    }

    @Test
    void passCompleteIsFalseUntilTheFinalBatch() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(2);
        cursor.startPass(List.of("a", "b", "c"), 4L);

        ChunkSweepCursor.SweepBatch<String> first = cursor.advance(handle -> true);
        assertFalse(first.passComplete());

        ChunkSweepCursor.SweepBatch<String> second = cursor.advance(handle -> true);
        assertTrue(second.passComplete());
    }

    @Test
    void unavailableChunkIsSkippedNotRetriedAndDoesNotAbortPass() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(3);
        cursor.startPass(List.of("a", "b", "c"), 5L);

        ChunkSweepCursor.SweepBatch<String> batch = cursor.advance(handle -> !handle.equals("b"));

        assertTrue(batch.passComplete());
        assertEquals(List.of("a", "c"), batch.visited());
    }

    @Test
    void skippedChunkIsNeverRevisitedInALaterBatch() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(1);
        cursor.startPass(List.of("a", "b", "c"), 6L);

        cursor.advance(handle -> !handle.equals("a"));
        ChunkSweepCursor.SweepBatch<String> second = cursor.advance(handle -> true);
        ChunkSweepCursor.SweepBatch<String> third = cursor.advance(handle -> true);

        assertEquals(List.of("b"), second.visited());
        assertEquals(List.of("c"), third.visited());
        assertTrue(third.passComplete());
    }

    @Test
    void chunksAddedToOriginalListAfterPassStartedAreNotIncludedInThePass() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(5);
        List<String> chunks = new ArrayList<>(List.of("a", "b"));
        cursor.startPass(chunks, 9L);
        chunks.add("c");

        ChunkSweepCursor.SweepBatch<String> batch = cursor.advance(handle -> true);

        assertEquals(List.of("a", "b"), batch.visited());
        assertTrue(batch.passComplete());
    }

    @Test
    void everyBatchOfOnePassReportsTheSameEpochId() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(1);
        cursor.startPass(List.of("a", "b", "c"), 42L);

        ChunkSweepCursor.SweepBatch<String> first = cursor.advance(handle -> true);
        ChunkSweepCursor.SweepBatch<String> second = cursor.advance(handle -> true);
        ChunkSweepCursor.SweepBatch<String> third = cursor.advance(handle -> true);

        assertEquals(42L, first.epochId());
        assertEquals(42L, second.epochId());
        assertEquals(42L, third.epochId());
    }

    @Test
    void startingNewPassWhileOneIsInFlightIsRefused() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(1);
        cursor.startPass(List.of("a", "b"), 1L);

        boolean accepted = cursor.startPass(List.of("x", "y"), 2L);

        assertFalse(accepted);
        assertTrue(cursor.isPassInFlight());
    }

    @Test
    void refusedPassDoesNotChangeTheInFlightPassEpochOrProgress() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(1);
        cursor.startPass(List.of("a", "b"), 1L);
        cursor.advance(handle -> true);

        cursor.startPass(List.of("x", "y"), 2L);

        ChunkSweepCursor.SweepBatch<String> batch = cursor.advance(handle -> true);
        assertEquals(List.of("b"), batch.visited());
        assertEquals(1L, batch.epochId());
        assertTrue(batch.passComplete());
    }

    @Test
    void newPassIsAcceptedAfterThePreviousPassCompletes() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(5);
        cursor.startPass(List.of("a"), 1L);
        cursor.advance(handle -> true);

        boolean accepted = cursor.startPass(List.of("x", "y"), 2L);

        assertTrue(accepted);
        assertTrue(cursor.isPassInFlight());
    }

    @Test
    void isPassInFlightReflectsLifecycle() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(1);
        assertFalse(cursor.isPassInFlight());

        cursor.startPass(List.of("a", "b"), 1L);
        assertTrue(cursor.isPassInFlight());

        cursor.advance(handle -> true);
        assertTrue(cursor.isPassInFlight());

        cursor.advance(handle -> true);
        assertFalse(cursor.isPassInFlight());
    }

    @Test
    void advanceWithoutAnyPassEverStartedThrowsIllegalState() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(1);

        assertThrows(IllegalStateException.class, () -> cursor.advance(handle -> true));
    }

    @Test
    void advanceAfterPassAlreadyCompletedThrowsIllegalStateUntilNewPassStarted() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(5);
        cursor.startPass(List.of("a"), 1L);
        cursor.advance(handle -> true);

        assertThrows(IllegalStateException.class, () -> cursor.advance(handle -> true));
    }

    @Test
    void singleBatchCompletesPassWhenBudgetCoversAllCapturedChunks() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(10);
        cursor.startPass(List.of("a", "b", "c"), 1L);

        ChunkSweepCursor.SweepBatch<String> batch = cursor.advance(handle -> true);

        assertEquals(List.of("a", "b", "c"), batch.visited());
        assertTrue(batch.passComplete());
    }

    @Test
    void passCompletesEvenWhenEveryChunkIsUnavailable() {
        ChunkSweepCursor<String> cursor = new ChunkSweepCursor<>(2);
        cursor.startPass(List.of("a", "b", "c"), 1L);

        ChunkSweepCursor.SweepBatch<String> first = cursor.advance(handle -> false);
        ChunkSweepCursor.SweepBatch<String> second = cursor.advance(handle -> false);

        assertEquals(List.of(), first.visited());
        assertFalse(first.passComplete());
        assertEquals(List.of(), second.visited());
        assertTrue(second.passComplete());
    }
}
