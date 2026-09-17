package com.itemguard.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteLifecycleFailureCrossProcessTest {

    @TempDir
    Path tempDir;

    @Test
    void interruptedStalledInitializationReturnsBoundedButRetainsOwnershipUntilExit()
        throws Exception {
        Child child = startChild("INIT_STALL", "init-stall");
        try {
            assertEquals(
                "INIT_STALL_RETURNED=true,INTERRUPTED_FAILURE=true",
                awaitText(
                    child,
                    "INIT_STALL_RETURNED=true,INTERRUPTED_FAILURE=true",
                    Duration.ofSeconds(3)
                )
            );
            assertOwned(child.database());
            killAndAssertReopen(child);
        } finally {
            stopForcibly(child);
        }
    }

    @Test
    void failedCloseOutcomeRemainsFalseOnRepeatedClose() throws Exception {
        Child child = startChild("DOUBLE_CLOSE", "double-close");
        try {
            assertEquals(
                "FIRST_CLOSE=false,SECOND_CLOSE=false",
                awaitText(child, "FIRST_CLOSE=false,SECOND_CLOSE=false", Duration.ofSeconds(10))
            );
            assertOwned(child.database());
            killAndAssertReopen(child);
        } finally {
            stopForcibly(child);
        }
    }

    @Test
    void processLockReleaseFailureIsReportedAndRetainedUntilExit() throws Exception {
        Child child = startChild("RELEASE_FAILURE", "release-failure");
        try {
            assertEquals(
                "RELEASE_CLOSE=false,SECOND_CLOSE=false,FAILURES=1",
                awaitText(
                    child,
                    "RELEASE_CLOSE=false,SECOND_CLOSE=false,FAILURES=1",
                    Duration.ofSeconds(10)
                )
            );
            assertOwned(child.database());
            killAndAssertReopen(child);
        } finally {
            stopForcibly(child);
        }
    }

    @Test
    void initializationReleaseFailurePreservesRootCauseAndRetainsUntilExit()
        throws Exception {
        Child child = startChild("INIT_RELEASE_FAILURE", "init-release-failure");
        try {
            assertEquals(
                "INIT_MESSAGE=original initialization failure,SUPPRESSED=1",
                awaitText(
                    child,
                    "INIT_MESSAGE=original initialization failure,SUPPRESSED=1",
                    Duration.ofSeconds(10)
                )
            );
            assertOwned(child.database());
            killAndAssertReopen(child);
        } finally {
            stopForcibly(child);
        }
    }

    @Test
    void throwingFailureHandlerCannotEscapeCloseOrSkipRetention() throws Exception {
        Child child = startChild("THROWING_FAILURE_HANDLER", "throwing-handler");
        try {
            assertEquals(
                "CLOSE=false",
                awaitText(child, "CLOSE=false", Duration.ofSeconds(10))
            );
            assertOwned(child.database());
            killAndAssertReopen(child);
        } finally {
            stopForcibly(child);
        }
    }

    private Child startChild(String mode, String prefix) throws Exception {
        Path database = tempDir.resolve(prefix + ".db");
        Path ready = tempDir.resolve(prefix + ".ready");
        Path entered = tempDir.resolve(prefix + ".entered");
        Path output = tempDir.resolve(prefix + ".log");
        String javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            isWindows() ? "java.exe" : "java"
        ).toString();
        String classPath = System.getProperty(
            "surefire.test.class.path",
            System.getProperty("java.class.path")
        );
        Process process = new ProcessBuilder(
            javaExecutable,
            "-cp",
            classPath,
            SqliteLifecycleFailureTestChild.class.getName(),
            mode,
            database.toString(),
            ready.toString(),
            entered.toString()
        )
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
        return new Child(process, database, ready, output);
    }

    private void assertOwned(Path database) {
        IllegalStateException rejected = assertThrows(
            IllegalStateException.class,
            () -> new SqliteConnectionOwner(database)
        );
        assertTrue(rejected.getMessage().contains("already owned"));
    }

    private void killAndAssertReopen(Child child) throws Exception {
        child.process().destroyForcibly();
        assertTrue(child.process().waitFor(10, TimeUnit.SECONDS));
        try (SqliteConnectionOwner reopened = openEventually(
            child.database(),
            Duration.ofSeconds(5)
        )) {
            assertEquals("ok", reopened.call(connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("PRAGMA integrity_check")) {
                    result.next();
                    return result.getString(1);
                }
            }));
        }
    }

    private SqliteConnectionOwner openEventually(Path database, Duration timeout)
        throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        IllegalStateException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                return new SqliteConnectionOwner(database);
            } catch (IllegalStateException failure) {
                lastFailure = failure;
                TimeUnit.MILLISECONDS.sleep(25L);
            }
        }
        throw new AssertionError("OS lock was not released after child exit", lastFailure);
    }

    private String awaitText(Child child, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String actual = "";
        while (System.nanoTime() < deadline) {
            if (Files.exists(child.ready())) {
                actual = Files.readString(child.ready());
                if (expected.equals(actual)) {
                    return actual;
                }
            }
            if (!child.process().isAlive()) {
                String output = Files.exists(child.output())
                    ? Files.readString(child.output())
                    : "";
                throw new AssertionError(
                    "Child exited before readiness; actual=" + actual + "; output=" + output
                );
            }
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        throw new AssertionError(
            "Child readiness timeout; expected=" + expected + "; actual=" + actual
        );
    }

    private void stopForcibly(Child child) throws Exception {
        if (child.process().isAlive()) {
            child.process().destroyForcibly();
            child.process().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record Child(Process process, Path database, Path ready, Path output) {}
}
