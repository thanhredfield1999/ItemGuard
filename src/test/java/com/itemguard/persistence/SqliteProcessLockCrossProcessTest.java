package com.itemguard.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProcessLockCrossProcessTest {

    @TempDir
    Path tempDir;

    @Test
    void childProcessOwnershipRejectsParentUntilCleanExit() throws Exception {
        ChildProcess child = startChild("HOLD", "clean");
        try {
            awaitFile(child.ready(), Duration.ofSeconds(10));
            assertEquals("READY", Files.readString(child.ready()));
            assertOwned(child.database());

            Files.writeString(child.stop(), "STOP");
            assertTrue(child.process().waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.process().exitValue(), child.outputText());

            try (SqliteConnectionOwner reopened = new SqliteConnectionOwner(child.database())) {
                assertEquals("ok", integrity(reopened));
                assertEquals(1, trackedRows(reopened));
            }
        } finally {
            stopForcibly(child);
        }
    }

    @Test
    void forceKilledChildReleasesOsLockAndPreservesCommittedRows() throws Exception {
        ChildProcess child = startChild("HOLD", "forced");
        try {
            awaitFile(child.ready(), Duration.ofSeconds(10));
            assertEquals("READY", Files.readString(child.ready()));
            assertOwned(child.database());

            child.process().destroyForcibly();
            assertTrue(child.process().waitFor(10, TimeUnit.SECONDS));

            try (SqliteConnectionOwner reopened = openEventually(
                child.database(),
                Duration.ofSeconds(5)
            )) {
                assertEquals("ok", integrity(reopened));
                assertEquals(1, trackedRows(reopened));
            }
        } finally {
            stopForcibly(child);
        }
    }

    @Test
    void closeTimeoutRetainsOwnershipUntilProcessExit() throws Exception {
        ChildProcess child = startChild("TIMEOUT", "timeout");
        try {
            awaitFile(child.ready(), Duration.ofSeconds(10));
            assertEquals("TIMEOUT_CLOSE=false", Files.readString(child.ready()));
            assertOwned(child.database());

            child.process().destroyForcibly();
            assertTrue(child.process().waitFor(10, TimeUnit.SECONDS));

            try (SqliteConnectionOwner reopened = openEventually(
                child.database(),
                Duration.ofSeconds(5)
            )) {
                assertEquals("ok", integrity(reopened));
                assertEquals(1, trackedRows(reopened));
            }
        } finally {
            stopForcibly(child);
        }
    }

    private ChildProcess startChild(String mode, String prefix) throws Exception {
        Path database = tempDir.resolve(prefix + ".db");
        Path ready = tempDir.resolve(prefix + ".ready");
        Path stop = tempDir.resolve(prefix + ".stop");
        Path operationEntered = tempDir.resolve(prefix + ".entered");
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
            SqliteProcessLockTestChild.class.getName(),
            mode,
            database.toString(),
            ready.toString(),
            stop.toString(),
            operationEntered.toString()
        )
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
        return new ChildProcess(process, database, ready, stop, output);
    }

    private void assertOwned(Path database) {
        IllegalStateException rejected = assertThrows(
            IllegalStateException.class,
            () -> new SqliteConnectionOwner(database)
        );
        assertTrue(rejected.getMessage().contains("already owned"));
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

    private String integrity(SqliteConnectionOwner owner) {
        return owner.call(connection -> {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("PRAGMA integrity_check")) {
                result.next();
                return result.getString(1);
            }
        });
    }

    private int trackedRows(SqliteConnectionOwner owner) {
        return owner.call(connection -> {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM tracked_items")) {
                result.next();
                return result.getInt(1);
            }
        });
    }

    private void awaitFile(Path file, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(file)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Child readiness timeout for " + file);
            }
            TimeUnit.MILLISECONDS.sleep(25L);
        }
    }

    private void stopForcibly(ChildProcess child) throws Exception {
        if (child.process().isAlive()) {
            child.process().destroyForcibly();
            child.process().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record ChildProcess(
        Process process,
        Path database,
        Path ready,
        Path stop,
        Path output
    ) {
        String outputText() throws Exception {
            return Files.exists(output) ? Files.readString(output) : "";
        }
    }
}
