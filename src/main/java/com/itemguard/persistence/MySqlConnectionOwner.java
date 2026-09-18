package com.itemguard.persistence;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns Premium MySQL work and the DataSource/pool behind it.
 *
 * <p>The production factory is Connector/J's {@code MysqlDataSource} behind HikariCP. The owner
 * borrows one connection for one transaction and closes it; Hikari, not this class, owns pooling.
 * The injectable factory remains for deterministic tests and for the controlled fixture.
 *
 * <p>D4 is deliberate: no retry, replay or local write buffer. A failed write is denied, and a
 * connection that cannot be closed or used is not silently reused.
 */
public final class MySqlConnectionOwner implements JdbcConnectionOwner {
    private static final int DEFAULT_CLOSE_TIMEOUT_SECONDS = 10;

    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    /** A factory that owns a pool or data source which must close with this owner. */
    public interface OwnedConnectionFactory extends ConnectionFactory, AutoCloseable {
        @Override
        default void close() throws SQLException {
        }
    }

    @FunctionalInterface
    public interface MySqlOperation<T> extends JdbcOperation<T> {
        @Override
        T apply(Connection connection) throws Exception;
    }

    private final ConnectionFactory factory;
    private final Consumer<Throwable> failureHandler;
    private final MySqlIdentityLock identityLock;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object submissionMonitor = new Object();
    private int pendingSubmissions;
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
        if (closeTimeoutSeconds < 1) {
            throw new IllegalArgumentException("closeTimeoutSeconds must be positive");
        }
        this.executor = Executors.newFixedThreadPool(Math.min(maximumPoolSize, 4), runnable -> {
            Thread thread = new Thread(runnable, "ItemGuard-MySQL");
            thread.setDaemon(true);
            return thread;
        });
        this.closeTimeoutSeconds = closeTimeoutSeconds;

        // Fail closed at construction: verify the server and install/validate the schema before
        // admitting any operation. A failed construction also closes an owned Hikari pool.
        try (Connection connection = factory.open()) {
            connection.setAutoCommit(false);
            MySqlSchemaManager schema = new MySqlSchemaManager();
            schema.requireDurableSession(connection);
            schema.initialize(connection);
        } catch (SQLException failure) {
            closeFactory(failure);
            executor.shutdownNow();
            throw new IllegalStateException(
                "ItemGuard could not open a usable MySQL session; refusing to start", failure);
        }
    }

    public void execute(JdbcOperation<Void> operation) {
        Objects.requireNonNull(operation, "operation");
        submit(() -> { call(operation); return null; });
    }

    public void flush() {
        synchronized (submissionMonitor) {
            while (pendingSubmissions > 0) {
                try {
                    submissionMonitor.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                        "Interrupted while waiting for ItemGuard MySQL work", interrupted);
                }
            }
        }
    }

    public <T> T call(JdbcOperation<T> work) {
        Objects.requireNonNull(work, "work");
        Connection connection = borrow();
        try {
            return runTransaction(connection, work);
        } catch (Exception failure) {
            report(failure);
            throw new IllegalStateException("ItemGuard database operation failed", failure);
        } finally {
            closeQuietly(connection);
        }
    }

    public <T> CompletableFuture<T> callAsync(JdbcOperation<T> work) {
        Objects.requireNonNull(work, "work");
        return submit(() -> call(work));
    }

    /** Runs work while holding the database row lock for the same transaction. */
    public <T> T callLocked(String code, MySqlIdentityLock.LockedIdentityWork<T> work) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(work, "work");
        Connection connection = borrow();
        try {
            return identityLock.withLockedIdentity(connection, code, work);
        } catch (NoSuchIdentityException absent) {
            throw new IllegalStateException(absent.getMessage(), absent);
        } catch (SQLException failure) {
            report(failure);
            throw new IllegalStateException("ItemGuard database operation failed", failure);
        } finally {
            closeQuietly(connection);
        }
    }

    public <T> CompletableFuture<T> callLockedAsync(
        String code,
        MySqlIdentityLock.LockedIdentityWork<T> work
    ) {
        return submit(() -> callLocked(code, work));
    }

    @Override
    public void executeIdentityLocked(String code, JdbcOperation<Void> operation) {
        callIdentityLockedAsync(code, operation);
    }

    @Override
    public <T> T callIdentityLocked(String code, JdbcOperation<T> operation) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(operation, "operation");
        return callLocked(code, connection -> applyLocked(operation, connection));
    }

    @Override
    public <T> CompletableFuture<T> callIdentityLockedAsync(
        String code,
        JdbcOperation<T> operation
    ) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(operation, "operation");
        return callLockedAsync(code, connection -> applyLocked(operation, connection));
    }

    @Override
    public <T> T callIdentityLockedOrCreate(String code, JdbcOperation<T> operation) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(operation, "operation");
        Connection connection = borrow();
        try {
            return identityLock.withLockedIdentityOrCreate(
                connection,
                code,
                locked -> applyLocked(operation, locked)
            );
        } catch (SQLException failure) {
            report(failure);
            throw new IllegalStateException("ItemGuard database operation failed", failure);
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public <T> CompletableFuture<T> callIdentityLockedOrCreateAsync(
        String code,
        JdbcOperation<T> operation
    ) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(operation, "operation");
        return submit(() -> callIdentityLockedOrCreate(code, operation));
    }

    private static <T> T applyLocked(JdbcOperation<T> operation, Connection connection)
        throws SQLException {
        try {
            return operation.apply(connection);
        } catch (SQLException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SQLException("ItemGuard identity-locked operation failed", failure);
        }
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
        try {
            if (factory instanceof AutoCloseable owned) {
                owned.close();
            }
        } catch (Exception failure) {
            report(failure);
        }
    }

    private <T> T runTransaction(Connection connection, JdbcOperation<T> work)
        throws Exception {
        try {
            T result = work.apply(connection);
            connection.commit();
            return result;
        } catch (Exception failure) {
            rollback(connection, failure);
            throw failure;
        }
    }

    private <T> CompletableFuture<T> submit(java.util.concurrent.Callable<T> work) {
        CompletableFuture<T> completion = new CompletableFuture<>();
        synchronized (submissionMonitor) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(
                    new RejectedExecutionException("ItemGuard MySQL owner is closed"));
            }
            pendingSubmissions++;
            try {
                executor.execute(() -> {
                    try {
                        completion.complete(work.call());
                    } catch (Throwable failure) {
                        report(failure);
                        completion.completeExceptionally(failure);
                    } finally {
                        synchronized (submissionMonitor) {
                            pendingSubmissions--;
                            submissionMonitor.notifyAll();
                        }
                    }
                });
            } catch (RejectedExecutionException rejected) {
                pendingSubmissions--;
                submissionMonitor.notifyAll();
                completion.completeExceptionally(rejected);
            }
        }
        return completion;
    }

    private Connection borrow() {
        if (closed.get()) {
            throw new RejectedExecutionException("ItemGuard MySQL owner is closed");
        }
        try {
            Connection connection = factory.open();
            try {
                connection.setAutoCommit(false);
                return connection;
            } catch (SQLException configurationFailure) {
                closeQuietly(connection);
                throw configurationFailure;
            }
        } catch (SQLException failure) {
            report(failure);
            throw new IllegalStateException(
                "ItemGuard could not obtain a MySQL connection; the operation is denied and "
                    + "nothing was queued for later", failure);
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
            // Best effort; the pool or connection is being abandoned either way.
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
     * Development/test factory only. The shipping path must use {@link #hikariDataSource} so the
     * driver is explicit and relocated rather than resolved through the global DriverManager.
     */
    public static ConnectionFactory driverManager(String url, String user, String password) {
        return () -> {
            Properties properties = new Properties();
            properties.setProperty("user", user);
            properties.setProperty("password", password);
            properties.setProperty("connectTimeout", "5000");
            properties.setProperty("socketTimeout", "30000");
            return DriverManager.getConnection(url, properties);
        };
    }

    /** The shipping Premium path: Connector/J DataSource behind HikariCP. */
    public static OwnedConnectionFactory hikariDataSource(
        String url,
        String user,
        String password,
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeoutMillis
    ) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize must be at least 1");
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException("minimumIdle must be between 0 and maximumPoolSize");
        }
        if (connectionTimeoutMillis < 250) {
            throw new IllegalArgumentException("connectionTimeoutMillis must be at least 250 ms");
        }

        MysqlDataSource mysql = new MysqlDataSource();
        mysql.setUrl(url);
        mysql.setUser(user);
        mysql.setPassword(password);

        HikariConfig config = new HikariConfig();
        config.setDataSource(mysql);
        config.setPoolName("ItemGuard-MySQL");
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(minimumIdle);
        config.setConnectionTimeout(connectionTimeoutMillis);
        config.setValidationTimeout(Math.min(connectionTimeoutMillis, 5_000L));
        config.setInitializationFailTimeout(connectionTimeoutMillis);
        config.setAutoCommit(false);
        HikariDataSource pool = new HikariDataSource(config);
        return new OwnedConnectionFactory() {
            @Override
            public Connection open() throws SQLException {
                return pool.getConnection();
            }

            @Override
            public void close() {
                pool.close();
            }
        };
    }

    private void closeFactory(Throwable originalFailure) {
        try {
            if (factory instanceof AutoCloseable owned) {
                owned.close();
            }
        } catch (Exception closeFailure) {
            originalFailure.addSuppressed(closeFailure);
        }
    }
}
