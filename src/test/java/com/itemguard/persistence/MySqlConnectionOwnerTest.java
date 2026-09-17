package com.itemguard.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MySQL connection owner, against a real server: what it installs, what it reuses, what it
 * refuses, and what it closes.
 *
 * <p>The two tests that carry the D4 decision are {@code aFailedWriteIsNotReplayed} (nothing is
 * buffered for later) and {@code theOwnerClosesEveryConnectionItOpened} (cleanup is verified, not
 * assumed).
 */
@Tag("mysql")
class MySqlConnectionOwnerTest {

    /**
     * The constructor opens one connection to verify the session and install the schema, and closes
     * it before the pool exists. Every count below is therefore "one for validation, then the pool"
     * and the assertions say so rather than hoping.
     */
    private static final int CONSTRUCTION_CONNECTION = 1;

    private static final String CODE = MySqlTestSupport.IDENTITY_CODE;
    private static final String UUID = MySqlTestSupport.IDENTITY_UUID;

    /** Counts connections so reuse and cleanup can be measured instead of believed. */
    private static final class CountingFactory implements MySqlConnectionOwner.ConnectionFactory {
        private final AtomicInteger opened = new AtomicInteger();
        private final List<Connection> created = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Connection open() throws SQLException {
            Connection connection = MySqlTestSupport.connect();
            opened.incrementAndGet();
            created.add(connection);
            return connection;
        }
    }

    /** The locked-work callback may only throw SQLException, so an interrupt becomes one. */
    private static void awaitOrFail(CountDownLatch latch) throws SQLException {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while the two writers were interleaving",
                interrupted);
        }
    }

    private static MySqlConnectionOwner ownerOn(CountingFactory factory) {
        return new MySqlConnectionOwner(factory, 4, failure -> {});
    }

    @Test
    @DisplayName("construction installs the schema and the version the code claims")
    void constructionInstallsTheSchema() throws Exception {
        MySqlTestSupport.freshSchema().close();
        CountingFactory factory = new CountingFactory();
        try (MySqlConnectionOwner owner = ownerOn(factory)) {
            int version = owner.call(connection -> {
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(
                         "SELECT schema_version FROM plugin_stats WHERE id = 1")) {
                    rows.next();
                    return rows.getInt(1);
                }
            });
            assertEquals(MySqlSchemaManager.CURRENT_SCHEMA_VERSION, version);
        }
    }

    @Test
    @DisplayName("a server that cannot be reached is refused at construction, with nothing cached")
    void anUnreachableServerIsRefused() {
        MySqlConnectionOwner.ConnectionFactory unreachable = MySqlConnectionOwner.driverManager(
            "jdbc:mysql://127.0.0.1:33317/itemguard_premium?connectTimeout=2000", "itemguard",
            "itemguard_test_password");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new MySqlConnectionOwner(unreachable, 2, ignored -> {}));
        assertTrue(failure.getMessage().contains("refusing to start"),
            "unexpected message: " + failure.getMessage());
        assertTrue(failure.getCause() instanceof SQLException,
            "the JDBC reason must be preserved for the operator");
    }

    @Test
    @DisplayName("a pooled connection is reused rather than opened per call")
    void connectionsAreReused() throws Exception {
        MySqlTestSupport.freshSchema().close();
        CountingFactory factory = new CountingFactory();
        try (MySqlConnectionOwner owner = ownerOn(factory)) {
            for (int index = 0; index < 5; index++) {
                owner.call(connection -> null);
            }
            assertEquals(CONSTRUCTION_CONNECTION + 1, factory.opened.get(),
                "five sequential calls must reuse one pooled connection");
            for (int index = 0; index < 10; index++) {
                owner.callAsync(connection -> null).get(20, TimeUnit.SECONDS);
            }
            assertEquals(CONSTRUCTION_CONNECTION + 1, factory.opened.get(),
                "and fifteen must not open fifteen: the count must not track the number of calls");
        }
    }

    @Test
    @DisplayName("every connection the owner opened is closed when it is closed")
    void theOwnerClosesEveryConnectionItOpened() throws Exception {
        MySqlTestSupport.freshSchema().close();
        CountingFactory factory = new CountingFactory();
        MySqlConnectionOwner owner = ownerOn(factory);
        owner.call(connection -> null);
        owner.callAsync(connection -> null).get(20, TimeUnit.SECONDS);
        owner.close();

        assertFalse(factory.created.isEmpty(), "the factory opened nothing to check");
        for (Connection connection : factory.created) {
            assertTrue(connection.isClosed(), "a connection ItemGuard opened outlived the owner");
        }
        assertThrows(RejectedExecutionException.class, () -> owner.call(connection -> null));
        CompletableFuture<Void> rejected = owner.callAsync(connection -> null);
        assertTrue(rejected.isCompletedExceptionally(),
            "work submitted after close must be refused, not queued");
    }

    @Test
    @DisplayName("failed work is not retried and not buffered: the row is untouched")
    void aFailedWriteIsNotReplayed() throws Exception {
        try (Connection setup = MySqlTestSupport.freshSchema()) {
            MySqlTestSupport.insertTrackedItem(setup, CODE, UUID);
            CountingFactory factory = new CountingFactory();
            try (MySqlConnectionOwner owner = ownerOn(factory)) {
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> owner.call(connection -> {
                        MySqlTestSupport.setDetectionCount(connection, CODE, 99);
                        throw new SQLException("write failed on purpose");
                    }));
                assertTrue(failure.getCause() instanceof SQLException);
                assertEquals(0, MySqlTestSupport.detectionCount(setup, CODE),
                    "the rolled-back write must not appear, and must not be replayed later");
                assertEquals(CONSTRUCTION_CONNECTION + 1, factory.opened.get(),
                    "a failure must not open more connections, which is what a retry loop does");
                owner.call(connection -> null);
            }
            assertEquals(0, MySqlTestSupport.detectionCount(setup, CODE),
                "nothing from the failed call may appear after the owner is closed either");
        }
    }

    @Test
    @DisplayName("two writers through the owner on one identity: both updates land")
    void theOwnerAndTheIdentityLockCompose() throws Exception {
        try (Connection setup = MySqlTestSupport.freshSchema()) {
            MySqlTestSupport.insertTrackedItem(setup, CODE, UUID);
            CountingFactory factory = new CountingFactory();
            try (MySqlConnectionOwner owner = ownerOn(factory)) {
                CountDownLatch bothRead = new CountDownLatch(2);
                List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    for (int index = 0; index < 2; index++) {
                        pool.execute(() -> {
                            try {
                                owner.callLocked(CODE, locked -> {
                                    int current = MySqlTestSupport.detectionCount(locked, CODE);
                                    bothRead.countDown();
                                    awaitOrFail(bothRead);
                                    MySqlTestSupport.setDetectionCount(locked, CODE, current + 1);
                                    return null;
                                });
                            } catch (Throwable failure) {
                                failures.add(failure);
                            }
                        });
                    }
                } finally {
                    pool.shutdown();
                    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
                }
                assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);
                assertEquals(2, MySqlTestSupport.detectionCount(setup, CODE),
                    "the owner hands out real connections, so the row lock must still serialise");
            }
        }
    }

    @Test
    @DisplayName("locking an identity that is gone is refused, and the connection is kept healthy")
    void lockingAnAbsentIdentityIsRefused() throws Exception {
        MySqlTestSupport.freshSchema().close();
        CountingFactory factory = new CountingFactory();
        try (MySqlConnectionOwner owner = ownerOn(factory)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> owner.callLocked(CODE, locked -> null));
            assertTrue(failure.getCause() instanceof NoSuchIdentityException,
                "the refusal must stay identifiable: " + failure.getCause());
            owner.call(connection -> null);
            assertEquals(CONSTRUCTION_CONNECTION + 1, factory.opened.get(),
                "the refusal must not have discarded the connection");
        }
    }
}
