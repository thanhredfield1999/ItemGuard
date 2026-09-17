package com.itemguard.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SqliteLifecycleFailureTestChild {

    private SqliteLifecycleFailureTestChild() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path database = Path.of(args[1]);
        Path ready = Path.of(args[2]);
        Path entered = Path.of(args[3]);

        if ("INIT_STALL".equals(mode)) {
            runInterruptedInitializationStall(database, ready, entered);
            return;
        }
        if ("RELEASE_FAILURE".equals(mode)) {
            runReleaseFailure(database, ready);
            return;
        }
        if ("INIT_RELEASE_FAILURE".equals(mode)) {
            runInitializationReleaseFailure(database, ready);
            return;
        }
        if ("THROWING_FAILURE_HANDLER".equals(mode)) {
            runThrowingFailureHandler(database, ready);
            return;
        }
        if ("DOUBLE_CLOSE".equals(mode)) {
            runDoubleClose(database, ready, entered);
            return;
        }
        throw new IllegalArgumentException("Unknown mode: " + mode);
    }

    private static void runInterruptedInitializationStall(
        Path database,
        Path ready,
        Path entered
    ) throws Exception {
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread constructor = new Thread(() -> {
            try {
                new SqliteConnectionOwner(
                    database,
                    failure -> {},
                    Duration.ofMillis(100L),
                    () -> {
                        try {
                            Files.writeString(entered, "ENTERED");
                            while (true) {
                                try {
                                    TimeUnit.SECONDS.sleep(1L);
                                } catch (InterruptedException ignored) {
                                    // Simulate a native initialization call that ignores interruption.
                                }
                            }
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    },
                    SqliteProcessLock::close
                );
                outcome.set(new AssertionError("stalled constructor unexpectedly succeeded"));
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        }, "stalled-owner-constructor");
        constructor.start();
        awaitFile(entered, Duration.ofSeconds(5));
        constructor.interrupt();
        constructor.join(1_000L);
        Throwable failure = outcome.get();
        Files.writeString(
            ready,
            "INIT_STALL_RETURNED=" + !constructor.isAlive()
                + ",INTERRUPTED_FAILURE=" + (failure instanceof IllegalStateException)
        );
        waitForever();
    }

    private static void runReleaseFailure(Path database, Path ready) throws Exception {
        List<Throwable> failures = new ArrayList<>();
        SqliteConnectionOwner owner = new SqliteConnectionOwner(
            database,
            failures::add,
            Duration.ofSeconds(1L),
            () -> {},
            ignored -> {
                throw new IllegalStateException("injected process-lock release failure");
            }
        );
        boolean firstClose = owner.close(Duration.ofSeconds(1L));
        boolean secondClose = owner.close(Duration.ofSeconds(1L));
        Files.writeString(
            ready,
            "RELEASE_CLOSE=" + firstClose
                + ",SECOND_CLOSE=" + secondClose
                + ",FAILURES=" + failures.size()
        );
        waitForever();
    }

    private static void runInitializationReleaseFailure(
        Path database,
        Path ready
    ) throws Exception {
        Throwable outcome;
        try {
            new SqliteConnectionOwner(
                database,
                failure -> {},
                Duration.ofSeconds(1L),
                () -> {
                    throw new IllegalStateException("original initialization failure");
                },
                ignored -> {
                    throw new IllegalStateException("injected process-lock release failure");
                }
            );
            outcome = new AssertionError("initialization unexpectedly succeeded");
        } catch (Throwable failure) {
            outcome = failure;
        }
        Files.writeString(
            ready,
            "INIT_MESSAGE=" + outcome.getMessage()
                + ",SUPPRESSED=" + outcome.getSuppressed().length
        );
        waitForever();
    }

    private static void runDoubleClose(
        Path database,
        Path ready,
        Path entered
    ) throws Exception {
        SqliteConnectionOwner owner = new SqliteConnectionOwner(database);
        owner.callAsync(connection -> {
            Files.writeString(entered, "ENTERED");
            waitForever();
            return null;
        });
        awaitFile(entered, Duration.ofSeconds(5));
        boolean firstClose = owner.close(Duration.ofMillis(50L));
        boolean secondClose = owner.close(Duration.ofMillis(50L));
        Files.writeString(
            ready,
            "FIRST_CLOSE=" + firstClose + ",SECOND_CLOSE=" + secondClose
        );
        waitForever();
    }

    private static void runThrowingFailureHandler(Path database, Path ready)
        throws Exception {
        SqliteConnectionOwner owner = new SqliteConnectionOwner(
            database,
            failure -> {
                throw new IllegalStateException("injected failure-handler failure");
            },
            Duration.ofSeconds(1L),
            () -> {},
            ignored -> {
                throw new IllegalStateException("injected process-lock release failure");
            }
        );
        boolean closeResult;
        try {
            closeResult = owner.close(Duration.ofSeconds(1L));
        } catch (Throwable escaped) {
            Files.writeString(ready, "ESCAPED=" + escaped.getMessage());
            waitForever();
            return;
        }
        Files.writeString(ready, "CLOSE=" + closeResult);
        waitForever();
    }

    private static void waitForever() throws InterruptedException {
        while (true) {
            TimeUnit.SECONDS.sleep(1L);
        }
    }

    private static void awaitFile(Path path, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(path)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("timed out waiting for " + path);
            }
            TimeUnit.MILLISECONDS.sleep(25L);
        }
    }
}
