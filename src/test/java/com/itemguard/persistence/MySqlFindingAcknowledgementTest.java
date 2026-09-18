package com.itemguard.persistence;

import com.itemguard.dupe.FindingAcknowledgement;
import com.itemguard.dupe.FindingRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema v10 adds the acknowledgement columns for duplicate findings.
 *
 * <p>Two things have to hold, and only the second one is obvious. A fresh database must carry the
 * columns. An <em>existing</em> v9 database — the shape every server that installed the first
 * Premium build has on disk — must gain them on startup without losing its findings: an upgrade that
 * only worked on fresh installs would leave those servers silently unable to acknowledge anything,
 * with the plugin reporting "not supported" for the wrong reason.
 *
 * <p>Requires the controlled fixture: {@code python tools/mysql-runtime/mysql_fixture.py start},
 * then {@code mvnw.cmd -Pmysql test}.
 */
@Tag("mysql")
class MySqlFindingAcknowledgementTest {

    private static final String CODE = "ACK0001";
    private static final String ITEM_UUID = "ffffffff-0000-0000-0000-000000000001";

    private static boolean columnExists(Connection connection, String table, String column)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * The finding table has a foreign key to {@code tracked_items(code)}, so an identity has to exist
     * before its findings do — the same shape SQLite enforces. Seeded once per test connection.
     */
    private static void seedTrackedItem(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                 "SELECT 1 FROM tracked_items WHERE code = ?")) {
            statement.setString(1, CODE);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return;
                }
            }
        }
        MySqlTestSupport.insertTrackedItem(connection, CODE, ITEM_UUID);
    }

    private static void recordFinding(Connection connection, long epoch) throws SQLException {
        recordFinding(connection, epoch, "epoch " + epoch + ": 2 locations");
    }

    /**
     * @param detail the finding's detail text, or null, so a test can drive the null flags of
     *               {@code detail} and {@code acknowledged_at} independently — the pair that hid the
     *               late-{@code wasNull()} bug on 2026-09-19.
     */
    private static void recordFinding(Connection connection, long epoch, String detail)
        throws SQLException {
        seedTrackedItem(connection);
        try (var statement = connection.prepareStatement("""
                INSERT INTO duplicate_findings
                    (code, item_uuid, scan_epoch, status, distinct_locations, action, created_at, detail)
                VALUES (?, ?, ?, 'CONFIRMED', 2, 'NOTIFY', ?, ?)
                """)) {
            statement.setString(1, CODE);
            statement.setString(2, ITEM_UUID);
            statement.setLong(3, epoch);
            statement.setLong(4, 1_700_000_000_000L + epoch);
            statement.setString(5, detail);
            statement.executeUpdate();
        }
        connection.commit();
    }

    @Test
    @DisplayName("a fresh schema carries the v10 acknowledgement columns")
    void freshSchemaCarriesTheColumns() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            assertTrue(columnExists(connection, "duplicate_findings", "acknowledged_at"),
                "acknowledged_at must exist on a fresh v10 schema");
            assertTrue(columnExists(connection, "duplicate_findings", "acknowledged_by"),
                "acknowledged_by must exist on a fresh v10 schema");
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                     "SELECT schema_version FROM plugin_stats WHERE id = 1")) {
                assertTrue(rows.next(), "fresh initialization did not seed plugin_stats");
                assertEquals(10, rows.getInt(1),
                    "the recorded version must match the columns this manager creates");
                assertEquals(MySqlSchemaManager.CURRENT_SCHEMA_VERSION, rows.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("an existing v9 database is upgraded in place instead of losing its findings")
    void existingV9DatabaseIsUpgraded() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            recordFinding(connection, 41L);

            // Wind the database back to the v9 shape: the columns and the version stamp a server that
            // installed before this change actually has on disk.
            MySqlTestSupport.execute(connection, """
                ALTER TABLE duplicate_findings
                    DROP COLUMN acknowledged_at,
                    DROP COLUMN acknowledged_by
                """);
            MySqlTestSupport.execute(connection, "UPDATE plugin_stats SET schema_version = 9 WHERE id = 1");
            connection.commit();
            assertFalse(columnExists(connection, "duplicate_findings", "acknowledged_at"),
                "fixture did not wind the schema back to v9");

            new MySqlSchemaManager().initialize(connection);
            connection.commit();

            assertTrue(columnExists(connection, "duplicate_findings", "acknowledged_at"),
                "startup on an existing v9 database must add the column");
            assertTrue(columnExists(connection, "duplicate_findings", "acknowledged_by"),
                "startup on an existing v9 database must add the column");
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                     "SELECT schema_version FROM plugin_stats WHERE id = 1")) {
                assertTrue(rows.next());
                assertEquals(MySqlSchemaManager.CURRENT_SCHEMA_VERSION, rows.getInt(1),
                    "the upgrade must move the recorded version forward");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM duplicate_findings WHERE code = '" + CODE + "'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "the upgrade must not touch existing findings");
            }
        }
    }

    @Test
    @DisplayName("acknowledging marks the unread findings once, with the actor, and reads back")
    void acknowledgingIsRecordedAndIdempotent() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            recordFinding(connection, 41L);
            recordFinding(connection, 42L);
        }
        try (MySqlConnectionOwner owner = MySqlTestSupport.owner()) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner, "ack-test");

            FindingAcknowledgement first =
                repository.acknowledgeFindings(CODE, "ThanhRedfield", 1_700_000_500_000L);
            assertTrue(first.supported(), "the MySQL backend must support acknowledgement");
            assertEquals(2, first.acknowledged(), "both unread findings must be marked");

            FindingAcknowledgement second =
                repository.acknowledgeFindings(CODE, "SomeoneElse", 1_700_000_600_000L);
            assertEquals(0, second.acknowledged(),
                "a second acknowledgement must not rewrite who read it first");

            List<FindingRecord> findings = repository.findingsFor(CODE, 20);
            assertEquals(2, findings.size());
            for (FindingRecord finding : findings) {
                assertTrue(finding.acknowledged(), "every finding must read back as acknowledged");
                assertEquals("ThanhRedfield", finding.acknowledgedBy(),
                    "the first actor must survive the second call");
                assertEquals(1_700_000_500_000L, finding.acknowledgedAt());
                assertNotNull(finding.detail());
            }
        }
    }

    @Test
    @DisplayName("an unread finding reads back unread whether or not it has a detail line")
    void findingsStartUnacknowledged() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            recordFinding(connection, 7L);
            recordFinding(connection, 8L, null);
        }
        try (MySqlConnectionOwner owner = MySqlTestSupport.owner()) {
            List<FindingRecord> findings =
                new ItemSqliteRepository(owner, "ack-test").findingsFor(CODE, 20);

            assertEquals(2, findings.size());
            for (FindingRecord finding : findings) {
                assertFalse(finding.acknowledged(),
                    "a fresh finding must not read as read, whatever its detail column holds; "
                        + "reading the null flag late made a non-null detail look like an "
                        + "acknowledgement (found by this gate on 2026-09-19)");
                assertNull(finding.acknowledgedAt());
                assertNull(finding.acknowledgedBy());
                assertEquals("CONFIRMED", finding.status());
                assertEquals(2, finding.distinctLocations());
            }
        }
    }

    @Test
    @DisplayName("an acknowledged finding reads back acknowledged whether or not it has a detail line")
    void acknowledgedFindingsReadBackAcknowledged() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            recordFinding(connection, 7L);
            recordFinding(connection, 8L, null);
            MySqlTestSupport.execute(connection, """
                UPDATE duplicate_findings
                SET acknowledged_at = 1700000500000, acknowledged_by = 'ThanhRedfield'
                WHERE code = 'ACK0001'
                """);
            connection.commit();
        }
        try (MySqlConnectionOwner owner = MySqlTestSupport.owner()) {
            List<FindingRecord> findings =
                new ItemSqliteRepository(owner, "ack-test").findingsFor(CODE, 20);

            assertEquals(2, findings.size());
            for (FindingRecord finding : findings) {
                assertTrue(finding.acknowledged(),
                    "an acknowledged finding must read acknowledged even with a null detail line");
                assertEquals(1_700_000_500_000L, finding.acknowledgedAt(),
                    "the timestamp must come from acknowledged_at, not from another column");
                assertEquals("ThanhRedfield", finding.acknowledgedBy());
            }
        }
    }

    @Test
    @DisplayName("an unknown code reads back as no findings rather than an error")
    void unknownCodeHasNoFindings() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            assertTrue(connection.isValid(2), "fixture schema must be up before the read");
        }
        try (MySqlConnectionOwner owner = MySqlTestSupport.owner()) {
            assertTrue(
                new ItemSqliteRepository(owner, "ack-test").findingsFor("NOTHERE", 20).isEmpty(),
                "an identity with no findings must read as empty"
            );
        }
    }
}
