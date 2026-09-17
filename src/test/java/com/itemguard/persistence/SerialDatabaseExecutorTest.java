package com.itemguard.persistence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialDatabaseExecutorTest {

    @Test
    void allOperationsRunOnOneOwnedThread() throws Exception {
        try (SerialDatabaseExecutor executor = new SerialDatabaseExecutor("ItemGuard-DB-Test")) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> Thread.currentThread().getName()));
            }

            Set<String> threadNames = new HashSet<>();
            for (Future<String> future : futures) {
                threadNames.add(future.get());
            }

            assertEquals(Set.of("ItemGuard-DB-Test"), threadNames);
        }
    }

    @Test
    void closeDrainsAcceptedOperationsBeforeReturning() {
        AtomicInteger completed = new AtomicInteger();
        SerialDatabaseExecutor executor = new SerialDatabaseExecutor("ItemGuard-DB-Drain");
        for (int i = 0; i < 50; i++) {
            executor.execute(completed::incrementAndGet);
        }

        assertTrue(executor.close(Duration.ofSeconds(5)));
        assertEquals(50, completed.get());
    }

    @Test
    void operationsAreRejectedAfterClose() {
        SerialDatabaseExecutor executor = new SerialDatabaseExecutor("ItemGuard-DB-Closed");
        assertTrue(executor.close(Duration.ofSeconds(5)));

        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
        assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> 1));
    }
}
