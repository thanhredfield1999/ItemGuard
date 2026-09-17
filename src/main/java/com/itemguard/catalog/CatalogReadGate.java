package com.itemguard.catalog;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

/** Shared admission across all catalog viewers, independent of tracking writes. */
public final class CatalogReadGate {
    private final LongSupplier clock;
    private boolean busy, used;
    private long lastStart;
    public CatalogReadGate(LongSupplier clock) { this.clock=java.util.Objects.requireNonNull(clock); }
    public synchronized <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> operation) {
        long now=clock.getAsLong();
        if (busy || (used && now-lastStart<1_000_000_000L)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Catalog busy; try again shortly"));
        }
        busy=true; used=true; lastStart=now;
        try {
            CompletableFuture<T> result=operation.get();
            result.whenComplete((value,failure)->release());
            return result;
        } catch (RuntimeException failure) {
            busy=false; return CompletableFuture.failedFuture(failure);
        }
    }
    private synchronized void release() { busy=false; }
}
