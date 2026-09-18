package com.itemguard.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class SqliteConnectionOwner implements JdbcConnectionOwner {

    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private final SerialDatabaseExecutor executor;
    private final Path databasePath;
    private final Consumer<Throwable> failureHandler;
    private final SqliteProcessLock processLock;
    private final Duration initializationCleanupTimeout;
    private final Consumer<SqliteProcessLock> processLockReleaser;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object admissionLock = new Object();
    private volatile boolean closeSucceeded;
    private volatile Throwable recoveryFailure;
    private Connection connection;

    public SqliteConnectionOwner(Path databasePath) {
        this(databasePath, failure -> {});
    }

    public SqliteConnectionOwner(Path databasePath, Consumer<Throwable> failureHandler) {
        this(
            databasePath,
            failureHandler,
            DEFAULT_CLOSE_TIMEOUT,
            () -> {},
            SqliteProcessLock::close
        );
    }

    SqliteConnectionOwner(
        Path databasePath,
        Consumer<Throwable> failureHandler,
        Duration initializationCleanupTimeout,
        Runnable beforeInitialization,
        Consumer<SqliteProcessLock> processLockReleaser
    ) {
        Objects.requireNonNull(databasePath, "databasePath");
        this.databasePath = databasePath.toAbsolutePath();
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.initializationCleanupTimeout = Objects.requireNonNull(
            initializationCleanupTimeout,
            "initializationCleanupTimeout"
        );
        Objects.requireNonNull(beforeInitialization, "beforeInitialization");
        this.processLockReleaser = Objects.requireNonNull(
            processLockReleaser,
            "processLockReleaser"
        );
        this.processLock = SqliteProcessLock.acquire(databasePath);
        this.executor = new SerialDatabaseExecutor("ItemGuard-DB");

        Future<Void> initialization = executor.submit(() -> {
            try {
                beforeInitialization.run();
                openConnection();
                new SqliteSchemaManager().initialize(connection);
                return null;
            } catch (Throwable failure) {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (SQLException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    } finally {
                        connection = null;
                    }
                }
                throw failure;
            }
        });
        try {
            awaitInitialization(initialization);
        } catch (RuntimeException failure) {
            if (cleanupAfterInitializationFailure(failure)) {
                try {
                    processLockReleaser.accept(processLock);
                } catch (RuntimeException releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                    processLock.retainUntilProcessExit();
                }
            } else {
                processLock.retainUntilProcessExit();
            }
            throw failure;
        }
    }

    public void execute(JdbcOperation<Void> operation) {
        synchronized (admissionLock) {
            requireOpen();
            executor.execute(() -> {
                try {
                    runTransaction(operation);
                } catch (RuntimeException failure) {
                    reportFailure(failure);
                }
            });
        }
    }

    public <T> T call(JdbcOperation<T> operation) {
        Future<T> future;
        synchronized (admissionLock) {
            requireOpen();
            future = executor.submit(() -> runTransaction(operation));
        }
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for ItemGuard database", interrupted);
        } catch (ExecutionException failure) {
            throw propagate(failure.getCause());
        }
    }

    public <T> CompletableFuture<T> callAsync(JdbcOperation<T> operation) {
        synchronized (admissionLock) {
            requireOpen();
            Objects.requireNonNull(operation, "operation");
            CompletableFuture<T> completion = new CompletableFuture<>();
            executor.execute(() -> {
                try {
                    completion.complete(runTransaction(operation));
                } catch (RuntimeException failure) {
                    reportFailure(failure);
                    completion.completeExceptionally(failure);
                }
            });
            return completion;
        }
    }

    public void flush() {
        call(connection -> null);
    }

    public synchronized boolean close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        Future<Void> closeConnection;
        synchronized (admissionLock) {
            if (!closed.compareAndSet(false, true)) {
                return closeSucceeded;
            }
            // Admission and enqueue are atomic relative to closing admission. All
            // accepted operations (including recovery) drain before this final task.
            closeConnection = executor.submit(() -> {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
                return null;
            });
        }

        try {
            closeConnection.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            processLock.retainUntilProcessExit();
            closeSucceeded = false;
            return false;
        } catch (ExecutionException | TimeoutException failure) {
            reportFailure(failure);
            processLock.retainUntilProcessExit();
            closeSucceeded = false;
            return false;
        }

        long remainingNanos = Math.max(0L, deadline - System.nanoTime());
        if (!executor.close(Duration.ofNanos(remainingNanos))) {
            processLock.retainUntilProcessExit();
            closeSucceeded = false;
            return false;
        }
        try {
            processLockReleaser.accept(processLock);
            closeSucceeded = true;
            return true;
        } catch (RuntimeException releaseFailure) {
            reportFailure(releaseFailure);
            processLock.retainUntilProcessExit();
            closeSucceeded = false;
            return false;
        }
    }

    @Override
    public void close() {
        close(DEFAULT_CLOSE_TIMEOUT);
    }

    private <T> T runTransaction(JdbcOperation<T> operation) {
        requireHealthyConnection();
        try {
            T result = operation.apply(connection);
            connection.commit();
            return result;
        } catch (Throwable failure) {
            rollback(failure);
            throw propagate(failure);
        }
    }

    private void rollback(Throwable originalFailure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
            // SQLite can auto-rollback an interrupted write while JDBC still reports
            // autoCommit=false. Never guess the native state or retry the operation.
            // Closing discards remaining uncommitted work; the process lock stays held.
            try {
                connection.close();
                connection = null;
                openConnection();
            } catch (Throwable failure) {
                recoveryFailure = failure;
                originalFailure.addSuppressed(failure);
            }
        }
    }

    private void openConnection() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            // Durability is stated, not inherited. These happen to be the driver's current
            // defaults, so setting them changes no behaviour today - the point is that a
            // future driver default would change it SILENTLY: writes would still succeed and
            // the log would stay clean, and only a real crash would reveal that the last
            // transactions never reached the disk. A record that can lose its newest entries
            // without saying so is worse than no record, because staff would trust it.
            //
            // FULL = fsync before a commit returns. DELETE = rollback journal, which is what
            // the power-cut fixture was verified against; WAL is also crash-safe but adds
            // -wal/-shm files that the artifact and fixture checks do not expect.
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA journal_mode = DELETE");
        }
        com.itemguard.catalog.CatalogText.registerIndexFunction(connection);
        connection.setAutoCommit(false);
    }

    private void requireHealthyConnection() {
        if (recoveryFailure != null) {
            throw new IllegalStateException("ItemGuard database recovery failed; restart required", recoveryFailure);
        }
    }

    private void requireOpen() {
        requireHealthyConnection();
        if (closed.get()) {
            throw new RejectedExecutionException("ItemGuard database owner is closed");
        }
    }

    private void awaitInitialization(Future<Void> initialization) {
        try {
            initialization.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while initializing ItemGuard database", interrupted);
        } catch (ExecutionException failure) {
            throw propagate(failure.getCause());
        }
    }

    private boolean cleanupAfterInitializationFailure(RuntimeException originalFailure) {
        long deadline = System.nanoTime() + initializationCleanupTimeout.toNanos();
        Future<Void> cleanup;
        try {
            cleanup = executor.submit(() -> {
                if (connection != null) {
                    try {
                        connection.close();
                    } finally {
                        connection = null;
                    }
                }
                return null;
            });
        } catch (RuntimeException submissionFailure) {
            originalFailure.addSuppressed(submissionFailure);
            return false;
        }

        boolean interrupted = Thread.interrupted();
        boolean cleanupSucceeded = false;
        try {
            while (true) {
                try {
                    long remainingNanos = Math.max(0L, deadline - System.nanoTime());
                    cleanup.get(remainingNanos, TimeUnit.NANOSECONDS);
                    cleanupSucceeded = true;
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException cleanupFailure) {
                    originalFailure.addSuppressed(cleanupFailure.getCause());
                    break;
                } catch (TimeoutException timeout) {
                    originalFailure.addSuppressed(new IllegalStateException(
                        "Timed out cleaning up ItemGuard database initialization",
                        timeout
                    ));
                    break;
                }
            }
            long remainingNanos = Math.max(0L, deadline - System.nanoTime());
            boolean terminated = executor.close(Duration.ofNanos(remainingNanos));
            if (!terminated) {
                originalFailure.addSuppressed(new IllegalStateException(
                    "ItemGuard database executor did not terminate after initialization failure"
                ));
            }
            return cleanupSucceeded && terminated;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        return new IllegalStateException("ItemGuard database operation failed", failure);
    }

    private void reportFailure(Throwable failure) {
        try {
            failureHandler.accept(failure);
        } catch (Throwable handlerFailure) {
            failure.addSuppressed(handlerFailure);
        }
    }

    @FunctionalInterface
    public interface SqliteOperation<T> extends JdbcOperation<T> {
    }
}
