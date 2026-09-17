package com.itemguard.dupe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

public final class ChunkSweepCursor<T> {

    private final int chunksPerTick;

    private List<T> capturedChunks = Collections.emptyList();
    private int position = 0;
    private long epochId;
    private boolean passInFlight = false;

    public ChunkSweepCursor(int chunksPerTick) {
        if (chunksPerTick <= 0) {
            throw new IllegalArgumentException("chunksPerTick must be positive, got " + chunksPerTick);
        }
        this.chunksPerTick = chunksPerTick;
    }

    public boolean startPass(List<? extends T> chunkHandles, long epochId) {
        if (passInFlight) {
            return false;
        }
        this.capturedChunks = new ArrayList<>(chunkHandles);
        this.position = 0;
        this.epochId = epochId;
        this.passInFlight = true;
        return true;
    }

    public boolean isPassInFlight() {
        return passInFlight;
    }

    public SweepBatch<T> advance(Predicate<? super T> availability) {
        if (!passInFlight) {
            throw new IllegalStateException("No sweep pass is in flight; call startPass first.");
        }

        List<T> visited = new ArrayList<>();
        int processed = 0;
        while (processed < chunksPerTick && position < capturedChunks.size()) {
            T chunk = capturedChunks.get(position);
            position++;
            processed++;
            if (availability.test(chunk)) {
                visited.add(chunk);
            }
        }

        boolean complete = position >= capturedChunks.size();
        if (complete) {
            passInFlight = false;
        }

        return new SweepBatch<>(List.copyOf(visited), epochId, complete);
    }

    public record SweepBatch<T>(List<T> visited, long epochId, boolean passComplete) {
    }
}
