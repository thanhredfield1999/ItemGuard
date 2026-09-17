package com.itemguard.dupe;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class ObservationEpochFinalizer {

    private final DuplicateAuditStore store;
    private final Consumer<Runnable> mainExecutor;
    private final Consumer<List<DuplicateFinding>> reporter;
    private final Consumer<Throwable> failureHandler;
    private final LongSupplier clock;
    private final AntiDupeActionPolicy actionPolicy = new AntiDupeActionPolicy();

    public ObservationEpochFinalizer(
        DuplicateAuditStore store,
        Consumer<Runnable> mainExecutor,
        Consumer<List<DuplicateFinding>> reporter,
        Consumer<Throwable> failureHandler,
        LongSupplier clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.mainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void complete(
        long scanEpoch,
        boolean antiDupeEnabled,
        String configuredAction,
        long detectionCooldownMillis
    ) {
        DuplicateAction action = actionPolicy.resolve(configuredAction, false);
        CompletableFuture<List<DuplicateFinding>> completion;
        try {
            completion = store.completeAndAudit(
                scanEpoch,
                antiDupeEnabled,
                action,
                Math.max(0L, detectionCooldownMillis),
                clock.getAsLong()
            );
        } catch (RuntimeException failure) {
            failureHandler.accept(failure);
            return;
        }
        completion.whenComplete((findings, failure) -> {
            if (failure != null) {
                failureHandler.accept(failure);
                return;
            }
            if (findings == null || findings.isEmpty()) {
                return;
            }
            try {
                mainExecutor.accept(() -> reporter.accept(findings));
            } catch (RuntimeException schedulingFailure) {
                failureHandler.accept(schedulingFailure);
            }
        });
    }

    @FunctionalInterface
    public interface DuplicateAuditStore {
        CompletableFuture<List<DuplicateFinding>> completeAndAudit(
            long scanEpoch,
            boolean antiDupeEnabled,
            DuplicateAction action,
            long detectionCooldownMillis,
            long completedAt
        );
    }
}
