package com.itemguard.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2's gate: two writers, one identity, no lost and no double write — on a real MySQL server.
 *
 * <p>The pair of tests at the top is the whole point. The first one runs a read-modify-write from
 * two connections without the lock and asserts that an update is <em>lost</em>; the second runs the
 * same workload through {@link MySqlIdentityLock} and asserts both updates land. Without the first,
 * the second would prove nothing: it would pass on a workload that never interleaved.
 *
 * <p>Start the fixture first (`python tools/mysql-runtime/mysql_fixture.py start`), or let
 * {@code scripts/run_mysql_schema_gate.py} do it and stop it afterwards.
 */
@Tag("mysql")
class MySqlIdentityLockTest {

    private static final String CODE = MySqlTestSupport.IDENTITY_CODE;
    private static final String UUID = MySqlTestSupport.IDENTITY_UUID;
    private static final String OTHER_CODE = "EF34GH";
    private static final String OTHER_UUID = "00000000-0000-0000-0000-000000000002";

    /**
     * The read-modify-write both writers run: read, pause (so the two can interleave), write. It
     * deliberately does not commit — the caller decides, which is what makes the two tests differ
     * in exactly one thing: whether the work ran inside the identity lock.
     */
    private static void increment(Connection connection, CountDownLatch bothRead, long pauseMillis,
                                 List<Throwable> failures) {
        try {
            int current = MySqlTestSupport.detectionCount(connection, CODE);
            bothRead.countDown();
            bothRead.await(pauseMillis, TimeUnit.MILLISECONDS);
            MySqlTestSupport.setDetectionCount(connection, CODE, current + 1);
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    /**
     * The work callback may only throw SQLException, so an interrupt becomes one: the caller is
     * told the work did not finish rather than the thread being released silently.
     */
    private static void awaitRelease(CountDownLatch release) throws SQLException {
        try {
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new SQLException("the test did not release the lock holder in time");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while holding the identity lock", interrupted);
        }
    }

    private static List<Throwable> runPair(ExecutorService pool, Runnable task) throws Exception {
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch finished = new CountDownLatch(2);
        for (int index = 0; index < 2; index++) {
            pool.execute(() -> {
                try {
                    task.run();
                } finally {
                    finished.countDown();
                }
            });
        }
        assertTrue(finished.await(60, TimeUnit.SECONDS), "the pair did not finish in time");
        return failures;
    }

    @Test
    @DisplayName("two writers without the lock lose an update — this is the hazard, not a theory")
    void withoutTheLockAnUpdateIsLost() throws Exception {
        try (Connection setup = MySqlTestSupport.freshSchema()) {
            MySqlTestSupport.insertTrackedItem(setup, CODE, UUID);
            CountDownLatch bothRead = new CountDownLatch(2);
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                runPair(pool, () -> {
                    try (Connection connection = MySqlTestSupport.connect()) {
                        connection.setAutoCommit(false);
                        increment(connection, bothRead, 2_000, failures);
                        connection.commit();
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            } finally {
                pool.shutdownNow();
            }
            assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);
            assertEquals(1, MySqlTestSupport.detectionCount(setup, CODE),
                "both writers read 0 and wrote 1: that is the lost update the lock exists to stop");
        }
    }

    @Test
    @DisplayName("the same two writers through the lock: both updates land")
    void theLockPreventsTheLostUpdate() throws Exception {
        try (Connection setup = MySqlTestSupport.freshSchema()) {
            MySqlTestSupport.insertTrackedItem(setup, CODE, UUID);
            CountDownLatch bothRead = new CountDownLatch(2);
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                runPair(pool, () -> {
                    try (Connection connection = MySqlTestSupport.connect()) {
                        connection.setAutoCommit(false);
                        new MySqlIdentityLock().withLockedIdentity(connection, CODE,
                            locked -> {
                                increment(locked, bothRead, 1_000, failures);
                                return null;
                            });
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            } finally {
                pool.shutdownNow();
            }
            assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);
            assertEquals(2, MySqlTestSupport.detectionCount(setup, CODE),
                "one writer blocked until the other committed, so no update was overwritten");
        }
    }

    @Test
    @DisplayName("the lock lives until commit, not until the read")
    void theLockIsHeldUntilCommit() throws Exception {
        try (Connection setup = MySqlTestSupport.freshSchema()) {
            MySqlTestSupport.insertTrackedItem(setup, CODE, UUID);
            Connection holder = MySqlTestSupport.connect();
            holder.setAutoCommit(false);
            CountDownLatch inWork = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Throwable> holderFailures = Collections.synchronizedList(new ArrayList<>());
            Thread holderThread = new Thread(() -> {
                try {
                    new MySqlIdentityLock().withLockedIdentity(holder, CODE, locked -> {
                        MySqlTestSupport.setDetectionCount(locked, CODE, 42);
                        inWork.countDown();
                        awaitRelease(release);
                        return null;
                    });
                } catch (Throwable failure) {
                    holderFailures.add(failure);
                }
            }, "lock-holder");
            holderThread.start();
            try {
                assertTrue(inWork.await(10, TimeUnit.SECONDS), "the holder never took the lock");

                try (Connection blocked = MySqlTestSupport.connect()) {
                    blocked.setAutoCommit(false);
                    long started = System.nanoTime();
                    SQLException failure = assertThrows(SQLException.class,
                        () -> new MySqlIdentityLock(1).withLockedIdentity(blocked, CODE,
                            locked -> null),
                        "a second writer must not be able to write while the first holds the lock");
                    long waitedMillis = (System.nanoTime() - started) / 1_000_000L;
                    assertTrue(failure.getMessage().toLowerCase().contains("lock wait timeout"),
                        "expected a lock wait timeout, got: " + failure.getMessage());
                    assertTrue(waitedMillis >= 900,
                        "the write should have blocked for about the configured second, waited "
                            + waitedMillis + " ms");
                }

                release.countDown();
                holderThread.join(30_000);
                assertFalse(holderThread.isAlive(), "the holder did not finish");
                assertTrue(holderFailures.isEmpty(), () -> "holder failed: " + holderFailures);
                assertEquals(42, MySqlTestSupport.detectionCount(setup, CODE),
                    "the holder's committed value must be the one that survives");
                try (Connection afterRelease = MySqlTestSupport.connect()) {
                    afterRelease.setAutoCommit(false);
                    long started = System.nanoTime();
                    new MySqlIdentityLock(5).withLockedIdentity(afterRelease, CODE, locked -> null);
                    long waitedMillis = (System.nanoTime() - started) / 1_000_000L;
                    assertTrue(waitedMillis < 2_000,
                        "the lock was released by the commit; waited " + waitedMillis + " ms");
                }
            } finally {
                release.countDown();
                holderThread.join(5_000);
                holder.close();
            }
        }
    }

    @Test
    @DisplayName("different identities do not block each other")
    void differentIdentitiesDoNotBlockEachOther() throws Exception {
        try (Connection setup = MySqlTestSupport.freshSchema()) {
            MySqlTestSupport.insertTrackedItem(setup, CODE, UUID);
            MySqlTestSupport.insertTrackedItem(setup, OTHER_CODE, OTHER_UUID);
            Connection holder = MySqlTestSupport.connect();
            holder.setAutoCommit(false);
            CountDownLatch inWork = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Throwable> holderFailures = Collections.synchronizedList(new ArrayList<>());
            Thread holderThread = new Thread(() -> {
                try {
                    new MySqlIdentityLock().withLockedIdentity(holder, CODE, locked -> {
                        inWork.countDown();
                        awaitRelease(release);
                        return null;
                    });
                } catch (Throwable failure) {
                    holderFailures.add(failure);
                }
            }, "lock-holder");
            holderThread.start();
            try {
                assertTrue(inWork.await(10, TimeUnit.SECONDS), "the holder never took the lock");
                try (Connection other = MySqlTestSupport.connect()) {
                    other.setAutoCommit(false);
                    long started = System.nanoTime();
                    new MySqlIdentityLock(5).withLockedIdentity(other, OTHER_CODE, locked -> null);
                    long waitedMillis = (System.nanoTime() - started) / 1_000_000L;
                    assertTrue(waitedMillis < 1_000,
                        "a different identity must not wait for this one; waited "
                            + waitedMillis + " ms");
                }
            } finally {
                release.countDown();
                holderThread.join(10_000);
                holder.close();
            }
            assertTrue(holderFailures.isEmpty(), () -> "holder failed: " + holderFailures);
        }
    }

    @Test
    @DisplayName("an identity that does not exist is refused, and nothing is created")
    void absentIdentityIsRefused() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            assertThrows(NoSuchIdentityException.class,
                () -> new MySqlIdentityLock().withLockedIdentity(connection, CODE, locked -> null));
            assertEquals(0, MySqlTestSupport.count(connection, "tracked_items"),
                "a refused lock must not leave a row behind");
        }
    }

    @Test
    @DisplayName("failed work rolls back, releases the lock, and hides nothing")
    void failedWorkRollsBackAndReleasesTheLock() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            MySqlTestSupport.insertTrackedItem(connection, CODE, UUID);
            SQLException failure = assertThrows(SQLException.class,
                () -> new MySqlIdentityLock().withLockedIdentity(connection, CODE, locked -> {
                    MySqlTestSupport.setDetectionCount(locked, CODE, 99);
                    throw new SQLException("work failed on purpose");
                }));
            assertEquals("work failed on purpose", failure.getMessage());
            assertEquals(0, MySqlTestSupport.detectionCount(connection, CODE),
                "the partial write must have been rolled back");
            long started = System.nanoTime();
            new MySqlIdentityLock(2).withLockedIdentity(connection, CODE, locked -> null);
            long waitedMillis = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(waitedMillis < 1_000,
                "the rollback must have released the lock; waited " + waitedMillis + " ms");
        }
    }

    @Test
    @DisplayName("a connection with autoCommit on is refused, because it would hold nothing")
    void autoCommitIsRefused() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            connection.setAutoCommit(true);
            SQLException failure = assertThrows(SQLException.class,
                () -> new MySqlIdentityLock().withLockedIdentity(connection, CODE, locked -> null));
            assertTrue(failure.getMessage().contains("autoCommit"),
                "unexpected message: " + failure.getMessage());
        }
    }

    @Test
    @DisplayName("a connection lost mid-transaction leaves no write and no held lock")
    void connectionLostMidTransactionLeavesNoWrite() throws Exception {
        try (Connection setup = MySqlTestSupport.freshSchema()) {
            MySqlTestSupport.insertTrackedItem(setup, CODE, UUID);
            Connection dying = MySqlTestSupport.connect();
            dying.setAutoCommit(false);
            SQLException failure = assertThrows(SQLException.class,
                () -> new MySqlIdentityLock().withLockedIdentity(dying, CODE, locked -> {
                    MySqlTestSupport.setDetectionCount(locked, CODE, 7);
                    locked.abort(Runnable::run);
                    return null;
                }),
                "a lost connection must surface as a failure, not as a silent success");
            assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank(),
                "the failure must be sayable to an operator");
            try (Connection verify = MySqlTestSupport.connect()) {
                assertEquals(0, MySqlTestSupport.detectionCount(verify, CODE),
                    "nothing from the aborted transaction may be visible");
                verify.setAutoCommit(false);
                long deadline = System.nanoTime() + 10_000_000_000L;
                SQLException last = null;
                while (System.nanoTime() < deadline) {
                    try {
                        new MySqlIdentityLock(1).withLockedIdentity(verify, CODE, locked -> null);
                        last = null;
                        break;
                    } catch (SQLException blocked) {
                        last = blocked;
                        Thread.sleep(200);
                    }
                }
                if (last != null) {
                    throw new AssertionError(
                        "the dead connection's lock was never released by the server", last);
                }
            }
        }
    }
}
