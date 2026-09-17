package com.itemguard.persistence;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

class SqliteShutdownAdmissionTest {
    @TempDir Path directory;
    enum Admission { EXECUTE, CALL, ASYNC }

    @ParameterizedTest @EnumSource(Admission.class)
    void closeCannotOvertakeAnAdmittedOperationAndLeakRecoveredConnection(Admission admission) throws Exception {
        var owner = new SqliteConnectionOwner(directory.resolve(admission + ".db"));
        var ownerExecutor = SqliteConnectionOwner.class.getDeclaredField("executor");
        ownerExecutor.setAccessible(true);
        var serial = (SerialDatabaseExecutor) ownerExecutor.get(owner);
        var executorField = SerialDatabaseExecutor.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        var real = (ExecutorService) executorField.get(serial);
        var intercepted = mock(ExecutorService.class, delegatesTo(real));
        executorField.set(serial, intercepted);
        var producerEntered = new CountDownLatch(1);
        var allowEnqueue = new CountDownLatch(1);
        var enqueued = new CountDownLatch(1);
        var connectionClosed = new CountDownLatch(1);
        var allowCloseReturn = new CountDownLatch(1);
        var producerFailure = new AtomicReference<Throwable>();
        var closeFailure = new AtomicReference<Throwable>();
        var observedConnection = new AtomicReference<Connection>();
        Runnable pause = () -> {
            producerEntered.countDown();
            await(allowEnqueue);
        };
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("admission-producer")) pause.run();
            real.execute(call.getArgument(0, Runnable.class));
            enqueued.countDown();
            return null;
        }).when(intercepted).execute(any(Runnable.class));
        doAnswer(call -> {
            Callable<?> task = call.getArgument(0);
            if (Thread.currentThread().getName().equals("admission-producer")) {
                pause.run();
                Future<?> result = real.submit(task);
                enqueued.countDown();
                return result;
            }
            return real.submit(() -> {
                Object result = task.call();
                connectionClosed.countDown();
                await(allowCloseReturn);
                return result;
            });
        }).when(intercepted).submit(any(Callable.class));
        var producer = new Thread(() -> {
            try {
                SqliteConnectionOwner.SqliteOperation<Void> work = c -> {
                    observedConnection.set(c);
                    try (var s = c.createStatement()) { s.execute("CREATE TABLE admission_witness(value INTEGER)"); }
                    return null;
                };
                switch (admission) {
                    case EXECUTE -> owner.execute(work);
                    case CALL -> owner.call(work);
                    case ASYNC -> owner.callAsync(work).get(5, TimeUnit.SECONDS);
                }
            } catch (Throwable failure) { producerFailure.set(failure); }
        }, "admission-producer");
        var closer = new Thread(() -> {
            try { assertTrue(owner.close(Duration.ofSeconds(5))); }
            catch (Throwable failure) { closeFailure.set(failure); }
        }, "admission-closer");
        try {
            producer.start();
            assertTrue(producerEntered.await(5, TimeUnit.SECONDS));
            closer.start();
            // Old code closes first; corrected admission makes close wait at its monitor.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (connectionClosed.getCount() != 0 && closer.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < deadline) Thread.yield();
            assertTrue(connectionClosed.getCount() == 0 || closer.getState() == Thread.State.BLOCKED);
            allowEnqueue.countDown();
            assertTrue(enqueued.await(5, TimeUnit.SECONDS));
            assertTrue(connectionClosed.await(5, TimeUnit.SECONDS));
            allowCloseReturn.countDown();
            closer.join(6000);
            producer.join(6000);
            assertFalse(closer.isAlive());
            assertFalse(producer.isAlive());
            assertNull(closeFailure.get());
            var field = SqliteConnectionOwner.class.getDeclaredField("connection");
            field.setAccessible(true);
            Connection remaining = (Connection) field.get(owner);
            assertTrue(remaining == null || remaining.isClosed(), "shutdown must not leave a recovered connection open");
            assertNull(producerFailure.get(), "admitted work must drain before shutdown");
            assertNotNull(observedConnection.get());
            try (var reopened = new SqliteConnectionOwner(directory.resolve(admission + ".db"))) {
                boolean persisted = reopened.call(c -> {
                    try (var s = c.createStatement(); var rs = s.executeQuery(
                            "SELECT name FROM sqlite_master WHERE name='admission_witness'")) { return rs.next(); }
                });
                assertTrue(persisted);
            }
        } finally {
            allowEnqueue.countDown();
            allowCloseReturn.countDown();
            producer.join(6000);
            closer.join(6000);
            owner.close(Duration.ofSeconds(5));
            real.shutdownNow();
            real.awaitTermination(5, TimeUnit.SECONDS);
            var field = SqliteConnectionOwner.class.getDeclaredField("connection");
            field.setAccessible(true);
            Connection remaining = (Connection) field.get(owner);
            if (remaining != null && !remaining.isClosed()) remaining.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
