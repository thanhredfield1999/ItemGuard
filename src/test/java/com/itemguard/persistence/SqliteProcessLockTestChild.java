package com.itemguard.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class SqliteProcessLockTestChild {

    private SqliteProcessLockTestChild() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path database = Path.of(args[1]);
        Path ready = Path.of(args[2]);
        Path stop = Path.of(args[3]);
        Path operationEntered = Path.of(args[4]);

        SqliteConnectionOwner owner = new SqliteConnectionOwner(database);
        owner.call(connection -> {
            try (var statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO tracked_items
                (code, item_uuid, created_at, last_seen_at)
                VALUES ('CHILD1', '00000000-0000-0000-0000-000000000901', 1, 1)
                """)) {
                statement.executeUpdate();
            }
            return null;
        });

        if ("TIMEOUT".equals(mode)) {
            owner.callAsync(connection -> {
                Files.writeString(operationEntered, "ENTERED");
                while (!Files.exists(stop)) {
                    TimeUnit.MILLISECONDS.sleep(25L);
                }
                return null;
            });
            awaitFile(operationEntered, Duration.ofSeconds(5));
            boolean closed = owner.close(Duration.ofMillis(50L));
            Files.writeString(ready, "TIMEOUT_CLOSE=" + closed);
            while (true) {
                TimeUnit.SECONDS.sleep(1L);
            }
        }

        Files.writeString(ready, "READY");
        while (!Files.exists(stop)) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        if (!owner.close(Duration.ofSeconds(5L))) {
            throw new IllegalStateException("child owner did not close cleanly");
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
