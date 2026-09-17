package com.itemguard.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteConnectionOwnerTest {

    @TempDir
    Path tempDir;

    @Test
    void closeDrainsWritesAndRestartReadsPersistedRows() {
        Path database = tempDir.resolve("restart.db");
        SqliteConnectionOwner owner = new SqliteConnectionOwner(database);
        for (int i = 0; i < 50; i++) {
            int index = i;
            owner.execute(connection -> {
                try (var statement = connection.prepareStatement("""
                    INSERT INTO tracked_items
                    (code, item_uuid, created_at, last_seen_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                    statement.setString(1, "CODE" + index);
                    statement.setString(2, "00000000-0000-0000-0000-" + String.format("%012d", index));
                    statement.setLong(3, index);
                    statement.setLong(4, index);
                    statement.executeUpdate();
                }
                return null;
            });
        }

        assertTrue(owner.close(Duration.ofSeconds(5)));

        try (SqliteConnectionOwner reopened = new SqliteConnectionOwner(database)) {
            int count = reopened.call(connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT COUNT(*) FROM tracked_items")) {
                    result.next();
                    return result.getInt(1);
                }
            });
            assertEquals(50, count);
        }
    }

    @Test
    void operationsAreRejectedAfterClose() {
        SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("closed.db"));
        assertTrue(owner.close(Duration.ofSeconds(5)));

        assertThrows(RejectedExecutionException.class,
            () -> owner.execute(connection -> null));
        assertThrows(RejectedExecutionException.class,
            () -> owner.call(connection -> 1));
    }

    @Test
    void secondOwnerForSameDatabaseIsRejectedUntilFirstCloses() {
        Path database = tempDir.resolve("single-writer.db");
        try (SqliteConnectionOwner first = new SqliteConnectionOwner(database)) {
            SqliteConnectionOwner[] unexpectedOwner = new SqliteConnectionOwner[1];
            IllegalStateException rejected;
            try {
                rejected = assertThrows(
                    IllegalStateException.class,
                    () -> unexpectedOwner[0] = new SqliteConnectionOwner(database)
                );
            } finally {
                if (unexpectedOwner[0] != null) {
                    unexpectedOwner[0].close();
                }
            }
            assertTrue(rejected.getMessage().contains("already owned"));
        }

        try (SqliteConnectionOwner reopened = new SqliteConnectionOwner(database)) {
            assertEquals("ok", reopened.call(connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("PRAGMA integrity_check")) {
                    result.next();
                    return result.getString(1);
                }
            }));
        }
    }

    @Test
    void initializationFailureClosesDatabaseBeforeReleasingProcessLock() throws Exception {
        Path database = tempDir.resolve("future-schema.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE plugin_stats (
                    id INTEGER PRIMARY KEY,
                    schema_version INT NOT NULL,
                    duplicates_detected INT DEFAULT 0,
                    last_updated BIGINT
                )
                """);
            statement.executeUpdate("""
                INSERT INTO plugin_stats
                (id, schema_version, duplicates_detected, last_updated)
                VALUES (1, %d, 0, 0)
                """.formatted(SqliteSchemaManager.CURRENT_SCHEMA_VERSION + 1));
        }

        IllegalStateException rejected = assertThrows(
            IllegalStateException.class,
            () -> new SqliteConnectionOwner(database)
        );
        assertTrue(rejected.getMessage().contains("database operation failed"));
        assertDoesNotThrow(() -> Files.delete(database));

        try (SqliteConnectionOwner reopened = new SqliteConnectionOwner(database)) {
            assertEquals("ok", reopened.call(connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("PRAGMA integrity_check")) {
                    result.next();
                    return result.getString(1);
                }
            }));
        }
    }

    @Test
    void interruptedInitializationDoesNotReleaseLockWhileWorkerIsStillRunning()
        throws Exception {
        Path database = tempDir.resolve("interrupted-initialization.db");
        try (SqliteConnectionOwner initialized = new SqliteConnectionOwner(database)) {
            assertEquals("ok", integrity(initialized));
        }

        AtomicReference<Throwable> constructorOutcome = new AtomicReference<>();
        try (var blocker = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = blocker.createStatement()) {
            statement.execute("BEGIN EXCLUSIVE");

            Thread constructor = new Thread(() -> {
                try {
                    new SqliteConnectionOwner(database);
                    constructorOutcome.set(new AssertionError(
                        "interrupted constructor unexpectedly succeeded"
                    ));
                } catch (Throwable failure) {
                    constructorOutcome.set(failure);
                }
            }, "interrupted-owner-constructor");
            constructor.start();
            awaitThreadState(constructor, Thread.State.WAITING, Duration.ofSeconds(5));

            constructor.interrupt();
            constructor.join(500L);

            SqliteProcessLock[] unexpectedLock = new SqliteProcessLock[1];
            try {
                IllegalStateException rejected = assertThrows(
                    IllegalStateException.class,
                    () -> unexpectedLock[0] = SqliteProcessLock.acquire(database)
                );
                assertTrue(rejected.getMessage().contains("already owned"));
            } finally {
                if (unexpectedLock[0] != null) {
                    unexpectedLock[0].close();
                }
            }

            statement.execute("ROLLBACK");
            constructor.join(10_000L);
            assertFalse(constructor.isAlive());
            assertTrue(constructorOutcome.get() instanceof IllegalStateException);
            assertTrue(constructorOutcome.get().getMessage().contains("Interrupted"));
        }

        try (SqliteProcessLock released = SqliteProcessLock.acquire(database)) {
            assertTrue(Files.exists(database));
        }
    }

    @Test
    void distinctDatabasesAndStaleSidecarDoNotCauseFalseOwnershipRejection()
        throws Exception {
        Path firstDatabase = tempDir.resolve("first.db");
        Path secondDirectory = Files.createDirectories(tempDir.resolve("other"));
        Path secondDatabase = secondDirectory.resolve("second.db");
        Files.writeString(
            firstDatabase.resolveSibling(firstDatabase.getFileName() + ".itemguard.lock"),
            "stale"
        );

        try (
            SqliteConnectionOwner first = new SqliteConnectionOwner(firstDatabase);
            SqliteConnectionOwner second = new SqliteConnectionOwner(secondDatabase)
        ) {
            assertEquals("ok", integrity(first));
            assertEquals("ok", integrity(second));
        }
    }

    @Test
    void normalizedAndAbsoluteAliasesShareOneOwnershipLock() {
        Path canonical = tempDir.resolve("alias.db").toAbsolutePath();
        Path alias = tempDir.resolve(".").resolve("alias.db");

        try (SqliteConnectionOwner first = new SqliteConnectionOwner(canonical)) {
            IllegalStateException rejected = assertThrows(
                IllegalStateException.class,
                () -> new SqliteConnectionOwner(alias)
            );
            assertTrue(rejected.getMessage().contains("already owned"));
        }
    }

    @Test
    void failedWriteRollsBackAndReportsError() {
        List<Throwable> failures = new ArrayList<>();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("rollback.db"), failures::add
        )) {
            owner.execute(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("""
                        INSERT INTO tracked_items
                        (code, item_uuid, created_at, last_seen_at)
                        VALUES ('GOOD01', '00000000-0000-0000-0000-000000000001', 1, 1)
                        """);
                    statement.executeUpdate("INSERT INTO missing_table VALUES (1)");
                }
                return null;
            });
            owner.flush();

            int count = owner.call(connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT COUNT(*) FROM tracked_items")) {
                    result.next();
                    return result.getInt(1);
                }
            });

            assertEquals(0, count);
            assertFalse(failures.isEmpty());
        }
    }

    @Test
    void connectionEnforcesForeignKeysForDurableSchema() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("foreign-keys.db")
        )) {
            assertThrows(IllegalStateException.class, () -> owner.call(connection -> {
                try (var statement = connection.prepareStatement("""
                    INSERT INTO item_snapshots
                    (code, snapshot_version, payload, sha256, captured_at)
                    VALUES ('MISSING', 1, X'01', zeroblob(32), 1)
                    """)) {
                    statement.executeUpdate();
                }
                return null;
            }));
        }
    }

    @Test
    void callAsyncReturnsBeforeTransactionCompletesAndPropagatesFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("async.db")
        )) {
            long started = System.nanoTime();
            CompletableFuture<String> success = owner.callAsync(connection -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test latch timeout");
                }
                return "committed";
            });
            long submitMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started
            );

            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(submitMillis < 100L);
            assertFalse(success.isDone());
            release.countDown();
            assertEquals("committed", success.get(2, TimeUnit.SECONDS));

            CompletableFuture<Void> failed = owner.callAsync(connection -> {
                throw new SQLException("boom");
            });
            assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> failed.get(2, TimeUnit.SECONDS)
            );
        }
    }

    private String integrity(SqliteConnectionOwner owner) {
        return owner.call(connection -> {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("PRAGMA integrity_check")) {
                result.next();
                return result.getString(1);
            }
        });
    }

    private void awaitThreadState(
        Thread thread,
        Thread.State expected,
        Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (thread.getState() == expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        throw new AssertionError(
            "thread did not reach " + expected + "; current=" + thread.getState()
        );
    }
}
