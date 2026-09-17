package com.itemguard.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns every MySQL connection ItemGuard opens, and is the place D4 actually happens.
 *
 * <p>The SQLite counterpart ({@link SqliteConnectionOwner}) can promise a great deal because there
 * is exactly one connection and exactly one writer, enforced by an OS sidecar lock. Neither is
 * true here: several servers are meant to share this database, and a connection can die in the
 * middle of a write. So this class is written around what it will <em>not</em> do:
 *
 * <ul>
 *   <li><b>No retry, no replay, no local buffer.</b> A failed write is a failed operation. Buffer
 *       ing writes to local disk for later replay would create a second, uncontrolled source of
 *       identities — the exact thing this plugin exists to prevent — and a retry cannot tell a
 *       rollback from a commit whose acknowledgement was lost.</li>
 *   <li><b>No silent degradation.</b> If the session cannot be shown to be durable and strict
 *       ({@link MySqlSchemaManager#requireDurableSession}), the owner refuses to construct rather
 *       than serving reads and writes on a server whose guarantees are unknown.</li>
 *   <li><b>No connection left behind.</b> {@link #close()} stops admitting work, waits for what is
 *       in flight, and closes every connection it opened — the workspace rule about owning cleanup
 *       applies to database handles as much as to servers.</li>
 * </ul>
 *
 * <p>Transactions are explicit at the call site, and there are two shapes on purpose:
 * {@link #call(MySqlOperation)} runs the work in a transaction the owner commits or rolls back,
 * while {@link #callLocked(String, MySqlIdentityLock.LockedIdentityWork)} hands the connection to
 * {@link MySqlIdentityLock}, which owns the transaction because the row lock has to be held for
 * exactly its duration. Wrapping the second shape in the first would produce a lock taken inside a
 * transaction the caller does not control.
 */
public final class MySqlConnectionOwner implements AutoCloseable {

    private static final int DEFAULT_CLOSE_TIMEOUT_SECONDS = 10;
    private static final long BORROW_TIMEOUT_MILLIS = 5_000;

    /** Opens a raw connection. A factory rather than a URL keeps this class testable and keeps the
     *  JDBC specifics in one place. */
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    /** Work that runs on a borrowed connection. */
    @FunctionalInterface
    public interface MySqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final ConnectionFactory factory;
    private final Consumer<Throwable> failureHandler;
    private final MySqlIdentityLock identityLock;
    private final Semaphore permits;
    private final BlockingQueue<Connection> idle;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int closeTimeoutSeconds;

    public MySqlConnectionOwner(ConnectionFactory factory, int maximumPoolSize,
                                Consumer<Throwable> failureHandler) {
        this(factory, maximumPoolSize, failureHandler, new MySqlIdentityLock(),
            DEFAULT_CLOSE_TIMEOUT_SECONDS);
    }

    MySqlConnectionOwner(ConnectionFactory factory, int maximumPoolSize,
                         Consumer<Throwable> failureHandler, MySqlIdentityLock identityLock,
                         int closeTimeoutSeconds) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.identityLock = Objects.requireNonNull(identityLock, "identityLock");
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize must be at least 1");
        }
        this.permits = new Semaphore(maximumPoolSize);
        this.idle = new ArrayBlockingQueue<>(maximumPoolSize);
        this.executor = Executors.newFixedThreadPool(Math.min(maximumPoolSize, 4), runnable -> {
            Thread thread = new Thread(runnable, "ItemGuard-MySQL");
            thread.setDaemon(true);
            return thread;
        });
        this.closeTimeoutSeconds = closeTimeoutSeconds;
        // Fail closed at construction: the first connection is used to verify the session's
        // durability and strictness and to install the schema. An owner that cannot do that has
        // nothing to offer, and saying so here is cheaper than failing on a player's inventory
        // event later.
        try (Connection connection = factory.open()) {
            connection.setAutoCommit(false);
            new MySqlSchemaManager().requireDurableSession(connection);
            new MySqlSchemaManager().initialize(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                "ItemGuard could not open a usable MySQL session; refusing to start", failure);
        }
    }

    public <T> T call(MySqlOperation<T> work) {
        Connection connection = borrow();
        boolean broken = false;
        try {
            return runTransaction(connection, work);
        } catch (SQLException failure) {
            broken = true;
            report(failure);
            throw new IllegalStateException("ItemGuard database operation failed", failure);
        } finally {
            release(connection, broken);
        }
    }

    public <T> CompletableFuture<T> callAsync(MySqlOperation<T> work) {
        return submit(() -> call(work));
    }

    /**
     * Runs {@code work} as the only writer of {@code code}: the identity row is locked for the
     * duration of the transaction, and the lock and the transaction are the same window.
     */
    public <T> T callLocked(String code, MySqlIdentityLock.LockedIdentityWork<T> work) {
        Connection connection = borrow();
        boolean broken = false;
        try {
            return identityLock.withLockedIdentity(connection, code, work);
        } catch (NoSuchIdentityException absent) {
            throw new IllegalStateException(absent.getMessage(), absent);
        } catch (SQLException failure) {
            broken = !isUsable(connection);
            report(failure);
            throw new IllegalStateException("ItemGuard database operation failed", failure);
        } finally {
            release(connection, broken);
        }
    }

    public <T> CompletableFuture<T> callLockedAsync(String code,
                                                    MySqlIdentityLock.LockedIdentityWork<T> work) {
        return submit(() -> callLocked(code, work));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(closeTimeoutSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        List<Connection> open = new ArrayList<>();
        idle.drainTo(open);
        for (Connection connection : open) {
            closeQuietly(connection);
        }
    }

    private <T> T runTransaction(Connection connection, MySqlOperation<T> work) throws SQLException {
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
        }
    }

    /**
     * Async work is refused through the returned future rather than by throwing: a caller that is
     * chaining futures would otherwise have to guard every call site, and the failure would arrive
     * in a place where the plugin cannot report it. {@link #call} is the synchronous shape and
     * throws there instead, because its caller is already in a position to handle it.
     */
    private <T> CompletableFuture<T> submit(java.util.concurrent.Callable<T> work) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                new RejectedExecutionException("ItemGuard MySQL owner is closed"));
        }
        CompletableFuture<T> completion = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    completion.complete(work.call());
                } catch (Throwable failure) {
                    report(failure);
                    completion.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            completion.completeExceptionally(rejected);
        }
        return completion;
    }

    private Connection borrow() {
        if (closed.get()) {
            throw new RejectedExecutionException("ItemGuard MySQL owner is closed");
        }
        boolean permitted;
        try {
            permitted = permits.tryAcquire(BORROW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for an ItemGuard connection",
                interrupted);
        }
        if (!permitted) {
            throw new IllegalStateException(
                "No ItemGuard MySQL connection became available within " + BORROW_TIMEOUT_MILLIS
                    + " ms; the operation is denied rather than queued");
        }
        boolean handedOver = false;
        try {
            Connection pooled = idle.poll();
            if (pooled != null && isUsable(pooled)) {
                handedOver = true;
                return pooled;
            }
            if (pooled != null) {
                // A pooled connection that no longer answers is discarded, not reused with a
                // warning: half-dead connections are how "it worked yesterday" reports start.
                closeQuietly(pooled);
            }
            Connection fresh = factory.open();
            try {
                fresh.setAutoCommit(false);
            } catch (SQLException configurationFailure) {
                closeQuietly(fresh);
                throw configurationFailure;
            }
            handedOver = true;
            return fresh;
        } catch (SQLException failure) {
            report(failure);
            throw new IllegalStateException(
                "ItemGuard could not obtain a MySQL connection; the operation is denied and "
                    + "nothing was queued for later", failure);
        } finally {
            if (!handedOver) {
                permits.release();
            }
        }
    }

    private void release(Connection connection, boolean broken) {
        if (broken || closed.get() || !isUsable(connection)) {
            closeQuietly(connection);
        } else {
            try {
                connection.setAutoCommit(false);
            } catch (SQLException reset) {
                closeQuietly(connection);
            }
            if (!idle.offer(connection)) {
                closeQuietly(connection);
            }
        }
        permits.release();
    }

    private boolean isUsable(Connection connection) {
        try {
            return !connection.isClosed() && connection.isValid(2);
        } catch (SQLException unusable) {
            return false;
        }
    }

    private void rollback(Connection connection, Throwable originalFailure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
        }
    }

    private void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closing is best effort; the connection is being abandoned either way.
        }
    }

    private void report(Throwable failure) {
        try {
            failureHandler.accept(failure);
        } catch (Throwable handlerFailure) {
            failure.addSuppressed(handlerFailure);
        }
    }

    /**
     * The development and test factory: DriverManager with the settings a shared database needs.
     *
     * <p><strong>Not the shipping path yet.</strong> It goes through the global
     * {@code DriverManager}, which is exactly the hazard `IG-R026` records for SQLite — another
     * plugin's driver can win the lookup, and the durability the plugin believes it has is then
     * not the durability it gets. The MySQL equivalent has the same shape, and `IG-R026` is
     * already declared a prerequisite before the MySQL backend is enabled. Before that happens
     * this factory must become a non-global one (instantiate the driver directly, relocated
     * during shading) and the relocation has to survive the LITE packaging gate.
     */
    public static ConnectionFactory driverManager(String url, String user, String password) {
        return () -> {
            Properties properties = new Properties();
            properties.setProperty("user", user);
            properties.setProperty("password", password);
            // Fail fast rather than hang a server thread on an unreachable host.
            properties.setProperty("connectTimeout", "5000");
            properties.setProperty("socketTimeout", "30000");
            return DriverManager.getConnection(url, properties);
        };
    }
}
