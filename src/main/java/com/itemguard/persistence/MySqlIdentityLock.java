package com.itemguard.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

/**
 * One writer per identity, arbitrated by the database (design decision D2 of
 * {@code docs/design/2026-09-16-premium-mysql-contract.md}).
 *
 * <p>On SQLite the single-writer model is a freebie: one process holds an OS sidecar lock and one
 * connection serialises everything. A shared MySQL database has no such thing — several servers
 * are <em>meant</em> to write to it — so the guarantee is re-created explicitly, at the only
 * granularity that matters: the identity row.
 *
 * <p>{@code SELECT … FOR UPDATE} on {@code tracked_items.code} takes an exclusive row lock inside
 * the caller's transaction. Every other writer of that identity blocks until the transaction
 * ends, so a read-modify-write on one identity cannot interleave. The lock is released by
 * {@code commit} or {@code rollback} — there is no separate unlock, and nothing to leak.
 *
 * <h2>What this class owns, and why that is the point</h2>
 *
 * The contract calls transaction scope a correctness concern: on SQLite the serial executor gave
 * a fixed window, and here the window is exactly the transaction. So this class — not the caller —
 * begins the work, takes the lock, commits and rolls back. A caller who took the lock and then
 * wrote in a <em>different</em> transaction would hold a lock that protects nothing, and that
 * mistake is very hard to see in review. The API therefore makes it unrepresentable.
 *
 * <h2>Fail-closed choices, stated</h2>
 *
 * <ul>
 *   <li><b>An identity that does not exist is refused</b> ({@link NoSuchIdentityException}), not
 *       created. A lock on a row that is not there is not a lock, and a write that proceeds
 *       believing it holds one is exactly the duplicate this plugin exists to prevent.</li>
 *   <li><b>The wait is bounded</b> ({@code innodb_lock_wait_timeout}, default
 *       {@value #DEFAULT_LOCK_WAIT_SECONDS} seconds) and the previous value is restored afterwards. A
 *       blocked writer must fail visibly rather than hold a server thread indefinitely: the caller
 *       denies the action and says so.</li>
 *   <li><b>There is no write buffer and no retry.</b> If the connection fails mid-transaction the
 *       operation is denied and nothing is replayed later from local disk — a replay buffer is a
 *       new duplication source, which is the one thing this product may not create. A retry would
 *       also be unsafe here: the caller cannot tell a failure that rolled back from one that
 *       committed and lost the acknowledgement.</li>
 *   <li><b>Isolation is left alone.</b> The server's default is not changed, and no behaviour
 *       depends on gap locks: on a missing row the call refuses (above) rather than relying on a
 *       lock that may or may not be taken depending on the isolation level.</li>
 * </ul>
 */
public final class MySqlIdentityLock {

    public static final int DEFAULT_LOCK_WAIT_SECONDS = 5;

    private final int lockWaitSeconds;

    public MySqlIdentityLock() {
        this(DEFAULT_LOCK_WAIT_SECONDS);
    }

    public MySqlIdentityLock(int lockWaitSeconds) {
        if (lockWaitSeconds < 1) {
            throw new IllegalArgumentException("Lock wait must be at least one second");
        }
        this.lockWaitSeconds = lockWaitSeconds;
    }

    public Duration lockWait() {
        return Duration.ofSeconds(lockWaitSeconds);
    }

    /**
     * Runs {@code work} as the only writer of {@code code}: the identity row is locked, the work
     * runs inside that transaction, and the transaction is committed when it returns.
     *
     * @throws NoSuchIdentityException when no row exists for {@code code}
     * @throws SQLException            when the lock cannot be taken, the work fails, or the commit
     *                                 fails; the transaction is rolled back first
     */
    public <T> T withLockedIdentity(Connection connection, String code, LockedIdentityWork<T> work)
        throws SQLException, NoSuchIdentityException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(work, "work");
        if (connection.getAutoCommit()) {
            throw new SQLException(
                "The identity lock only means something inside a transaction; a connection with "
                    + "autoCommit on would release it immediately."
            );
        }
        int previousWait = currentLockWait(connection);
        setLockWait(connection, lockWaitSeconds);
        try {
            if (!lockIdentity(connection, code)) {
                connection.rollback();
                throw new NoSuchIdentityException(code);
            }
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException failure) {
                rollback(connection, failure);
                throw failure;
            } catch (RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            } catch (Error failure) {
                rollback(connection, failure);
                throw failure;
            }
        } finally {
            restoreLockWait(connection, previousWait);
        }
    }

    /** Same transaction shape, but allows the mint path to insert a previously absent identity. */
    public <T> T withLockedIdentityOrCreate(
        Connection connection,
        String code,
        LockedIdentityWork<T> work
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(work, "work");
        if (connection.getAutoCommit()) {
            throw new SQLException("The identity lock requires autoCommit=false");
        }
        int previousWait = currentLockWait(connection);
        setLockWait(connection, lockWaitSeconds);
        try {
            lockIdentity(connection, code);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException failure) {
                rollback(connection, failure);
                throw failure;
            } catch (RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            } catch (Error failure) {
                rollback(connection, failure);
                throw failure;
            }
        } finally {
            restoreLockWait(connection, previousWait);
        }
    }

    private boolean lockIdentity(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT code FROM tracked_items WHERE code = ? FOR UPDATE")) {
            statement.setString(1, code);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private int currentLockWait(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT @@innodb_lock_wait_timeout")) {
            if (rows.next()) return rows.getInt(1);
        } catch (SQLException unavailable) {
            return DEFAULT_LOCK_WAIT_SECONDS;
        }
        return DEFAULT_LOCK_WAIT_SECONDS;
    }

    private void setLockWait(Connection connection, int seconds) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION innodb_lock_wait_timeout = " + seconds);
        }
    }

    /**
     * Restoring the timeout must never replace the failure that is already travelling: a broken
     * connection here is reported as a suppressed exception, not as the outcome of the operation.
     */
    private void restoreLockWait(Connection connection, int previousWait) {
        try {
            setLockWait(connection, previousWait);
        } catch (SQLException ignored) {
            // The connection is already unusable; the caller is about to see that anyway.
        }
    }

    private void rollback(Connection connection, Throwable originalFailure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
        }
    }

    /** Work that runs while this process is the only writer of one identity. */
    @FunctionalInterface
    public interface LockedIdentityWork<T> {
        T apply(Connection connection) throws SQLException;
    }
}
