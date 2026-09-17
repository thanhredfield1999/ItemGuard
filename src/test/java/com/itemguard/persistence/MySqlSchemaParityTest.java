package com.itemguard.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contracts about the MySQL schema that hold without a server, so they run in the normal build.
 *
 * <p>What the tagged MySQL class proves is behaviour; this file proves the constructs that carry
 * the invariants are still in the source, because a regression here would otherwise stay green on
 * every machine that has no MySQL to run against.
 *
 * <p>Every negative assertion is made against the source with comments removed. The project has
 * already shipped one gate that a comment could satisfy ("a comment mentioning the language
 * cleared the literal below it"), and repeating that shape in a new gate would be careless.
 */
class MySqlSchemaParityTest {

    private static final Path SOURCE =
        Path.of("src/main/java/com/itemguard/persistence/MySqlSchemaManager.java");

    private static String source() throws Exception {
        return Files.readString(SOURCE);
    }

    /** Java comments removed: prose about SQL is not SQL. */
    private static String code() throws Exception {
        return stripped(SOURCE);
    }

    private static String stripped(Path path) throws Exception {
        String source = Files.readString(path);
        StringBuilder stripped = new StringBuilder();
        boolean block = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char previous = index == 0 ? '\0' : source.charAt(index - 1);
            if (block) {
                if (previous == '*' && current == '/') block = false;
                continue;
            }
            if (previous == '/' && current == '*') {
                block = true;
                stripped.setLength(stripped.length() - 1);
                continue;
            }
            if (previous == '/' && current == '/') {
                while (index < source.length() && source.charAt(index) != '\n') index++;
                stripped.append('\n');
                continue;
            }
            stripped.append(current);
        }
        return stripped.toString();
    }

    @Test
    @DisplayName("the MySQL schema is exactly one migration ahead, and the extra is server_id")
    void mysqlSchemaIsOneMigrationAhead() throws Exception {
        assertEquals(SqliteSchemaManager.CURRENT_SCHEMA_VERSION + 1,
            MySqlSchemaManager.CURRENT_SCHEMA_VERSION,
            "MySQL carries server_id, which a single-server SQLite install has nothing to record "
                + "in; moving one ladder without the other is a decision, not a detail");
        assertTrue(code().contains("server_id"),
            "the migration the MySQL ladder is ahead by is the server column");
    }

    @Test
    @DisplayName("the two partial unique indexes are present as generated-column keys")
    void partialIndexesAreReplacedByGeneratedColumnKeys() throws Exception {
        String code = code();
        assertTrue(code.contains("active_identity_lock"), "reclaim identity lock missing");
        assertTrue(code.contains("UNIQUE KEY idx_reclaim_identity_lock"),
            "the identity lock is not enforced by a unique key");
        assertTrue(code.contains("prepared_source_lock"), "publication source lock missing");
        assertTrue(code.contains("UNIQUE KEY idx_tag_publication_source_lock"),
            "the source lock is not enforced by a unique key");
        assertTrue(code.contains("'PENDING', 'PREPARED', 'COMMITTED'"),
            "the identity lock does not name the blocking states");
        assertTrue(code.contains("WHEN state = 'PREPARED'"),
            "the source lock is not conditional on the prepared state");
    }

    @Test
    @DisplayName("every table and every generated key is compared byte-wise")
    void comparisonIsByteWise() throws Exception {
        String code = code();
        assertEquals(9, code.split("DEFAULT CHARSET=utf8mb4 COLLATE=%s", -1).length - 1,
            "every table must pin the binary NO PAD collation: a table left on the "
                + "case-insensitive default is a different identity rule");
        assertEquals(2, code.split("COLLATE utf8mb4_0900_bin", -1).length - 1,
            "both generated lock columns must state the collation explicitly");
        assertFalse(code.contains("utf8mb4_general_ci"),
            "a case-insensitive collation would make AB12CD and ab12cd one identity");
    }

    @Test
    @DisplayName("snapshot payloads are not capped at the 65,535-byte MySQL BLOB")
    void payloadColumnsAreNotPlainBlobs() throws Exception {
        String code = code();
        assertEquals(2, code.split("payload MEDIUMBLOB", -1).length - 1,
            "both snapshot payload columns must hold the 1 MiB the codec accepts");
        assertFalse(code.contains("payload BLOB"),
            "BLOB stops at 65,535 bytes; the snapshot codec is configured with 1 MiB");
    }

    @Test
    @DisplayName("MySQL-only syntax, and no SQLite syntax, in the DDL")
    void sqliteOnlySyntaxIsAbsent() throws Exception {
        String code = code();
        assertFalse(code.contains("CREATE INDEX IF NOT EXISTS"),
            "MySQL rejects CREATE INDEX IF NOT EXISTS; the index must be looked up first");
        assertTrue(code.contains("historyIndexExists"),
            "the index existence check is what makes re-running initialization safe");
        assertFalse(code.contains("PRAGMA"), "PRAGMA is SQLite-only");
        assertFalse(code.contains("AUTOINCREMENT"), "use AUTO_INCREMENT on MySQL");
        assertFalse(code.contains("INSERT OR IGNORE"), "use INSERT IGNORE on MySQL");
        assertTrue(code.contains("MEDIUMBLOB") && code.contains("ENGINE=InnoDB"),
            "the DDL must be an InnoDB schema");
    }

    @Test
    @DisplayName("the identity lock is SELECT ... FOR UPDATE, and nothing else in that path")
    void identityLockUsesRowLockingAndNoReplay() throws Exception {
        String lock = stripped(Path.of(
            "src/main/java/com/itemguard/persistence/MySqlIdentityLock.java"));
        assertTrue(lock.contains("SELECT code FROM tracked_items WHERE code = ? FOR UPDATE"),
            "the whole guarantee is this row lock; without FOR UPDATE the lock takes nothing");
        assertTrue(lock.contains("connection.commit()") && lock.contains("connection.rollback()"),
            "the helper must own the transaction, because a lock taken outside it protects "
                + "nothing");
        assertTrue(lock.contains("innodb_lock_wait_timeout"),
            "the wait must be bounded, so a blocked writer fails visibly instead of hanging");
        for (String forbidden : new String[] {"retry", "replay", "buffer", "queue"}) {
            assertFalse(lock.toLowerCase().contains(forbidden),
                "the lock path must not contain " + forbidden + ": a local write buffer or a "
                    + "retry is a new duplication source");
        }
    }

    @Test
    @DisplayName("the MySQL tests are tagged, and the default build excludes them")
    void mysqlTestsAreTaggedAndExcludedByDefault() throws Exception {
        String test = Files.readString(Path.of(
            "src/test/java/com/itemguard/persistence/MySqlSchemaInvariantTest.java"));
        assertTrue(test.contains("@Tag(\"mysql\")"),
            "the MySQL test class must be tagged so the default build does not require a server");
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<groups>!mysql</groups>"),
            "the default build must exclude the mysql tag");
        assertTrue(pom.contains("<id>mysql</id>"),
            "the profile that runs the MySQL tests is what turns the tag into a gate");
        assertTrue(pom.contains("<failIfNoTests>true</failIfNoTests>"),
            "a tag filter matching nothing must not count as a passing gate");
    }
}
