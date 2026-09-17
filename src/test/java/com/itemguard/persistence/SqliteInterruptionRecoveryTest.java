package com.itemguard.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.ProgressHandler;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SqliteInterruptionRecoveryTest {
    @TempDir Path directory;

    @Test
    void failedConnectionCloseRejectsQueuedAndNewWorkWithoutReleasingOwnership() throws Exception {
        Path database = directory.resolve("uncertain-close.db");
        var owner = new SqliteConnectionOwner(database);
        var allowClose = new java.util.concurrent.atomic.AtomicBoolean();
        var executed = new java.util.concurrent.atomic.AtomicBoolean();
        var queued = new AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
        try {
            assertThrows(IllegalStateException.class, () -> owner.call(c -> {
                Connection proxy = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, method, args) -> {
                        if (method.getName().equals("rollback")
                            || (method.getName().equals("close") && !allowClose.get())) {
                            throw new java.sql.SQLException("injected uncertain close/rollback");
                        }
                        try { return method.invoke(c, args); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
                var field = SqliteConnectionOwner.class.getDeclaredField("connection");
                field.setAccessible(true);
                field.set(owner, proxy);
                queued.set(owner.callAsync(next -> { executed.set(true); return null; }));
                throw new IllegalStateException("original operation failed");
            }));
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> queued.get().get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(executed.get());
            assertThrows(IllegalStateException.class, () -> owner.call(c -> null));
            assertThrows(IllegalStateException.class, () -> new SqliteConnectionOwner(database));
        } finally {
            allowClose.set(true);
            assertTrue(owner.close(java.time.Duration.ofSeconds(5)));
        }
    }

    @Test
    void interruptedWriteDiscardsConnectionAndNextTransactionRemainsAtomic() throws Exception {
        Path database = directory.resolve("interrupt.db");
        AtomicReference<Connection> interrupted = new AtomicReference<>();
        try (var owner = new SqliteConnectionOwner(database)) {
            owner.call(c -> {
                try (var s = c.createStatement()) { s.execute("CREATE TABLE recovery_probe(value INTEGER)"); }
                return null;
            });
            var failure = assertThrows(IllegalStateException.class, () -> owner.call(c -> {
                interrupted.set(c);
                ProgressHandler.setHandler(c, 10, new ProgressHandler() {
                    @Override protected int progress() { return 1; }
                });
                try (var s = c.createStatement()) {
                    s.executeUpdate("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<10000) INSERT INTO recovery_probe SELECT x FROM n");
                } finally { ProgressHandler.clearHandler(c); }
                return null;
            }));
            assertTrue(failure.getCause().getMessage().contains("SQLITE_INTERRUPT"));
            assertTrue(interrupted.get().isClosed(), "uncertain JDBC transaction must not be reused");
            assertThrows(IllegalStateException.class, () -> owner.call(c -> {
                try (var s = c.createStatement()) { s.executeUpdate("INSERT INTO recovery_probe VALUES(77)"); }
                throw new IllegalStateException("abort next write");
            }));
            owner.call(c -> {
                assertNotSame(interrupted.get(), c);
                assertFalse(c.getAutoCommit());
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM recovery_probe")) {
                    assertTrue(r.next()); assertEquals(0, r.getInt(1));
                }
                try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA foreign_keys")) {
                    assertTrue(r.next()); assertEquals(1, r.getInt(1));
                }
                try (var s = c.createStatement()) { s.executeUpdate("INSERT INTO recovery_probe VALUES(42)"); }
                return null;
            });
            assertThrows(IllegalStateException.class, () -> new SqliteConnectionOwner(database));
        }
        try (var owner = new SqliteConnectionOwner(database)) {
            int persisted = owner.call(c -> {
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT value FROM recovery_probe")) {
                    assertTrue(r.next()); int value = r.getInt(1); assertFalse(r.next()); return value;
                }
            });
            assertEquals(42, persisted);
        }
    }
}
