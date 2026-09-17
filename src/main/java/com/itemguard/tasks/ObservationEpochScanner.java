package com.itemguard.tasks;

import java.util.function.LongConsumer;

public final class ObservationEpochScanner<T> {

    private final SourceScanner<T> playerScanner;
    private final SourceScanner<T> containerScanner;
    private final LongConsumer finalizer;

    public ObservationEpochScanner(
        SourceScanner<T> playerScanner,
        SourceScanner<T> containerScanner,
        LongConsumer finalizer
    ) {
        this.playerScanner = playerScanner;
        this.containerScanner = containerScanner;
        this.finalizer = finalizer;
    }

    public void scan(long scanEpoch, Iterable<? extends T> sources) {
        for (T source : sources) {
            playerScanner.scan(source, scanEpoch);
            containerScanner.scan(source, scanEpoch);
        }
        finalizer.accept(scanEpoch);
    }

    @FunctionalInterface
    public interface SourceScanner<T> {
        void scan(T source, long scanEpoch);
    }
}
