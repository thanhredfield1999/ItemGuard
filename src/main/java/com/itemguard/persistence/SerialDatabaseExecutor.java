package com.itemguard.persistence;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class SerialDatabaseExecutor implements AutoCloseable {

    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private final ExecutorService executor;

    public SerialDatabaseExecutor(String threadName) {
        Objects.requireNonNull(threadName, "threadName");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    public void execute(Runnable task) {
        executor.execute(task);
    }

    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    public boolean close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        executor.shutdown();
        try {
            if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            executor.shutdownNow();
            return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        close(DEFAULT_CLOSE_TIMEOUT);
    }
}
