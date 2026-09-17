package com.itemguard.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Durability settings must be stated, not inherited.
 *
 * <p>A power-cut fixture (`tools/lite-runtime/power_cut.py`) killed the JVM mid-write and found
 * every record intact. That run passed on the JDBC driver's defaults, because nothing in
 * ItemGuard ever set {@code synchronous} or {@code journal_mode}. Relying on a default is fine
 * until the day a driver upgrade changes it — and the failure would be silent: writes would
 * still succeed, the log would stay clean, and only an actual crash would reveal that the last
 * transactions were never on disk.
 *
 * <p>This plugin exists to be the record of what happened to an item. A record that can lose
 * its most recent entries without saying so is worse than no record, because staff would trust
 * it. So the two pragmas that decide crash behaviour are pinned here.
 *
 * <p>Values checked against SQLite's own encoding:
 * <ul>
 *   <li>{@code synchronous} 2 = FULL — fsync before each commit completes.
 *   <li>{@code journal_mode} = {@code delete} — the rollback journal this build was verified
 *       with. WAL would also be crash-safe but changes the file set on disk (a {@code -wal} and
 *       {@code -shm} beside the database), which the artifact and fixture checks do not expect.
 * </ul>
 */
class SqliteDurabilityPragmaTest {

    @Test
    @DisplayName("synchronous is FULL, so a commit means the bytes reached the disk")
    void synchronousIsFull(@TempDir Path dir) throws Exception {
        try (var owner = new SqliteConnectionOwner(dir.resolve("durability.db"))) {
            assertEquals(2, pragma(owner, "synchronous"),
                "synchronous must be FULL (2); NORMAL would let a crash lose committed writes");
        }
    }

    @Test
    @DisplayName("journal mode is the rollback journal the crash fixture was run against")
    void journalModeIsPinned(@TempDir Path dir) throws Exception {
        try (var owner = new SqliteConnectionOwner(dir.resolve("journal.db"))) {
            assertEquals("delete", pragmaText(owner, "journal_mode"),
                "changing the journal mode changes the on-disk file set and invalidates the "
                    + "power-cut evidence");
        }
    }

    @Test
    @DisplayName("foreign keys stay on - the existing guarantee must not regress")
    void foreignKeysRemainEnabled(@TempDir Path dir) throws Exception {
        try (var owner = new SqliteConnectionOwner(dir.resolve("fk.db"))) {
            assertEquals(1, pragma(owner, "foreign_keys"));
        }
    }

    private int pragma(SqliteConnectionOwner owner, String name) throws Exception {
        return Integer.parseInt(pragmaText(owner, name));
    }

    private String pragmaText(SqliteConnectionOwner owner, String name) throws Exception {
        return owner.call(connection -> {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("PRAGMA " + name)) {
                rows.next();
                return rows.getString(1);
            }
        });
    }
}
