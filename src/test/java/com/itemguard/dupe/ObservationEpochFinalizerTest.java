package com.itemguard.dupe;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationEpochFinalizerTest {

    @Test
    void asyncFindingIsReportedOnMainExecutorWithNotifyOnlyAction() {
        CompletableFuture<List<DuplicateFinding>> databaseCompletion = new CompletableFuture<>();
        AtomicReference<Boolean> enabledArgument = new AtomicReference<>();
        AtomicReference<DuplicateAction> actionArgument = new AtomicReference<>();
        Queue<Runnable> mainThread = new ArrayDeque<>();
        List<DuplicateFinding> reported = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        UUID itemUuid = UUID.randomUUID();
        DuplicateFinding finding = new DuplicateFinding(
            itemUuid,
            "AB12CD",
            88L,
            DuplicateStatus.CONFIRMED,
            2,
            DuplicateAction.NOTIFY,
            2_000L,
            "distinct_locations=2"
        );
        ObservationEpochFinalizer finalizer = new ObservationEpochFinalizer(
            (epoch, enabled, action, cooldown, completedAt) -> {
                assertEquals(88L, epoch);
                assertEquals(2_000L, completedAt);
                assertEquals(5_000L, cooldown);
                enabledArgument.set(enabled);
                actionArgument.set(action);
                return databaseCompletion;
            },
            mainThread::add,
            reported::addAll,
            failures::add,
            () -> 2_000L
        );

        finalizer.complete(88L, true, "REMOVE_ALL", 5_000L);
        assertTrue(mainThread.isEmpty());

        databaseCompletion.complete(List.of(finding));
        assertEquals(1, mainThread.size());
        assertTrue(reported.isEmpty());
        mainThread.remove().run();

        assertEquals(Boolean.TRUE, enabledArgument.get());
        assertEquals(DuplicateAction.NOTIFY, actionArgument.get());
        assertEquals(List.of(finding), reported);
        assertTrue(failures.isEmpty());
    }
}
