package com.itemguard.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1's gate: the invariants the SQLite schema enforces with partial unique indexes must be
 * enforced by the MySQL schema too, against a real server — and this class fails rather than
 * skips when there is none, because a skipped invariant test is a claim nobody checks.
 *
 * <p>Start the fixture first ({@code python tools/mysql-runtime/mysql_fixture.py start}) and run
 * with {@code mvnw.cmd -Pmysql test}. The default build excludes the {@code mysql} tag.
 *
 * <p>What a passing run here does <em>not</em> prove: multi-server concurrency (M2),
 * cross-server findings (M3), or anything about a Paper server. This is schema-level evidence.
 */
@Tag("mysql")
class MySqlSchemaInvariantTest {

    private static final String DEFAULT_URL =
        "jdbc:mysql://127.0.0.1:33316/itemguard_premium"
            + "?allowPublicKeyRetrieval=true&sslMode=PREFERRED&connectionTimeZone=UTC";

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
            setting("IG_MYSQL_TEST_URL", DEFAULT_URL),
            setting("IG_MYSQL_TEST_USER", "itemguard"),
            setting("IG_MYSQL_TEST_PASSWORD", "itemguard_test_password")
        );
    }

    /** Connects, drops every table of a previous run, and initializes the schema. */
    private static Connection freshSchema() throws SQLException {
        Connection connection = connect();
        connection.setAutoCommit(false);
        dropAllTables(connection);
        new MySqlSchemaManager().initialize(connection);
        return connection;
    }

    private static void dropAllTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            try (ResultSet rows = statement.executeQuery("""
                    SELECT TABLE_NAME FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
                    """)) {
                while (rows.next()) tables.add(rows.getString(1));
            }
            for (String table : tables) statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        connection.commit();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void insertTrackedItem(Connection connection, String code, String uuid)
        throws SQLException {
        execute(connection, """
            INSERT INTO tracked_items (code, item_uuid, created_at, last_seen_at)
            VALUES ('%s', '%s', 1, 1)
            """.formatted(code, uuid));
        connection.commit();
    }

    private static void insertClaim(Connection connection, String claimId, String player,
                                    String code, String state) throws SQLException {
        execute(connection, """
            INSERT INTO reclaim_claims
            (claim_id, idempotency_key, player_uuid, code, state, requested_at, updated_at)
            VALUES ('%s', 'key-%s', '%s', '%s', '%s', 1, 1)
            """.formatted(claimId, claimId, player, code, state));
    }

    /** tag_publications has three more unique keys, so every fixture row needs its own code/uuid. */
    private static void insertPublication(Connection connection, String publicationId,
                                          String sourceKey, String code, String state)
        throws SQLException {
        execute(connection, """
            INSERT INTO tag_publications
            (publication_id, source_key, source_digest, code, item_uuid, created_item_at,
             last_seen_at, detection_count, snapshot_version, payload, sha256, captured_at,
             state, created_at, updated_at)
            VALUES ('%s', '%s', X'00', '%s', '%s', 1, 1, 0, 1, X'00', X'00', 1, '%s', 1, 1)
            """.formatted(publicationId, sourceKey, code, "00000000-0000-0000-0000-%012d".formatted(
                Math.abs(publicationId.hashCode())), state));
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    @Test
    @DisplayName("the fresh schema is the same schema, at the same version, with the lock columns")
    void freshSchemaCarriesTheSameTablesAndVersion() throws Exception {
        try (Connection connection = freshSchema()) {
            for (String table : new String[] {"tracked_items", "item_history", "item_observations",
                "duplicate_findings", "item_search_requests", "item_snapshots", "reclaim_claims",
                "tag_publications", "plugin_stats"}) {
                try (var statement = connection.prepareStatement("""
                        SELECT 1 FROM information_schema.TABLES
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                        """)) {
                    statement.setString(1, table);
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next(), "missing table " + table);
                    }
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                     "SELECT schema_version FROM plugin_stats WHERE id = 1")) {
                rows.next();
                assertEquals(SqliteSchemaManager.CURRENT_SCHEMA_VERSION, rows.getInt(1));
            }
            for (String column : new String[] {"active_identity_lock", "prepared_source_lock"}) {
                try (var statement = connection.prepareStatement("""
                        SELECT EXTRA FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = ?
                        """)) {
                    statement.setString(1, column);
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next(), "missing generated column " + column);
                        assertTrue(rows.getString(1).contains("STORED GENERATED"),
                            column + " must be a stored generated column, was " + rows.getString(1));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("initialization is idempotent, because MySQL DDL commits as it goes")
    void initializeRunsTwiceWithoutFailing() throws Exception {
        try (Connection connection = freshSchema()) {
            new MySqlSchemaManager().initialize(connection);
            new MySqlSchemaManager().initialize(connection);
            assertEquals(1, count(connection, "plugin_stats"));
            new MySqlSchemaManager().validateHistoryIndex(connection);
        }
    }

    @Test
    @DisplayName("one active reclaim claim per identity, exactly as the partial index enforced")
    void oneActiveClaimPerIdentity() throws Exception {
        try (Connection connection = freshSchema()) {
            insertTrackedItem(connection, "AB12CD", "00000000-0000-0000-0000-000000000001");
            for (String state : new String[] {"PENDING", "PREPARED", "COMMITTED"}) {
                dropAllTables(connection);
                new MySqlSchemaManager().initialize(connection);
                insertTrackedItem(connection, "AB12CD", "00000000-0000-0000-0000-000000000001");
                insertClaim(connection, "claim-a", "player-1", "AB12CD", state);
                connection.commit();
                assertThrows(SQLIntegrityConstraintViolationException.class,
                    () -> { insertClaim(connection, "claim-b", "player-1", "AB12CD", "PENDING");
                            connection.commit(); },
                    "a second active claim must be refused while the first is " + state);
                connection.rollback();
                execute(connection, "UPDATE reclaim_claims SET state = 'DENIED' "
                    + "WHERE claim_id = 'claim-a'");
                connection.commit();
                insertClaim(connection, "claim-c", "player-1", "AB12CD", "PENDING");
                connection.commit();
                assertEquals(2, count(connection, "reclaim_claims"),
                    "a denied claim must not keep blocking the identity");
            }
        }
    }

    @Test
    @DisplayName("claims of different identities, or different players, do not collide")
    void differentIdentitiesOrPlayersDoNotCollide() throws Exception {
        try (Connection connection = freshSchema()) {
            insertTrackedItem(connection, "AB12CD", "00000000-0000-0000-0000-000000000001");
            insertTrackedItem(connection, "EF34GH", "00000000-0000-0000-0000-000000000002");
            insertClaim(connection, "claim-a", "player-1", "AB12CD", "PENDING");
            insertClaim(connection, "claim-b", "player-2", "AB12CD", "PENDING");
            insertClaim(connection, "claim-c", "player-1", "EF34GH", "PENDING");
            connection.commit();
            assertEquals(3, count(connection, "reclaim_claims"));
        }
    }

    @Test
    @DisplayName("one in-flight publication per physical source, committed ones may repeat")
    void onePreparedPublicationPerSource() throws Exception {
        try (Connection connection = freshSchema()) {
            insertTrackedItem(connection, "AB12CD", "00000000-0000-0000-0000-000000000001");
            insertTrackedItem(connection, "EF34GH", "00000000-0000-0000-0000-000000000002");
            insertTrackedItem(connection, "GH56IJ", "00000000-0000-0000-0000-000000000003");
            insertPublication(connection, "pub-1", "source-A", "AB12CD", "PREPARED");
            connection.commit();
            assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> { insertPublication(connection, "pub-2", "source-A", "EF34GH", "PREPARED");
                        connection.commit(); },
                "two in-flight publications for one physical source must be refused");
            connection.rollback();
            insertPublication(connection, "pub-3", "source-A", "EF34GH", "COMMITTED");
            insertPublication(connection, "pub-4", "source-B", "GH56IJ", "PREPARED");
            connection.commit();
            assertEquals(3, count(connection, "tag_publications"));
        }
    }

    @Test
    @DisplayName("identity comparison is byte-wise: case and trailing space are different identities")
    void identityComparisonIsByteWise() throws Exception {
        try (Connection connection = freshSchema()) {
            insertTrackedItem(connection, "AB12CD", "00000000-0000-0000-0000-000000000001");
            insertTrackedItem(connection, "ab12cd", "00000000-0000-0000-0000-000000000002");
            insertTrackedItem(connection, "AB12CD ", "00000000-0000-0000-0000-000000000003");
            assertEquals(3, count(connection, "tracked_items"),
                "a case-insensitive or PAD SPACE collation would collapse these identities");
            insertClaim(connection, "claim-a", "player-1", "AB12CD", "PENDING");
            insertClaim(connection, "claim-b", "player-1", "ab12cd", "PENDING");
            insertClaim(connection, "claim-c", "player-1", "AB12CD ", "PENDING");
            connection.commit();
            assertEquals(3, count(connection, "reclaim_claims"),
                "the generated lock column must compare byte-wise too");
        }
    }

    @Test
    @DisplayName("a snapshot past the 65,535-byte BLOB limit is stored, not rejected")
    void snapshotLargerThanBlobIsStored() throws Exception {
        try (Connection connection = freshSchema()) {
            insertTrackedItem(connection, "AB12CD", "00000000-0000-0000-0000-000000000001");
            byte[] payload = new byte[100_000];
            java.util.Arrays.fill(payload, (byte) 7);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO item_snapshots (code, snapshot_version, payload, sha256, captured_at)
                    VALUES ('AB12CD', 1, ?, ?, 1)
                    """)) {
                statement.setBytes(1, payload);
                statement.setBytes(2, new byte[32]);
                statement.executeUpdate();
            }
            connection.commit();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                     "SELECT OCTET_LENGTH(payload) FROM item_snapshots WHERE code = 'AB12CD'")) {
                rows.next();
                assertEquals(100_000, rows.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("a wrong history index is rejected, not just created")
    void wrongHistoryIndexIsRejected() throws Exception {
        try (Connection connection = freshSchema()) {
            new MySqlSchemaManager().validateHistoryIndex(connection);
            execute(connection, "DROP INDEX idx_history_identity_time ON item_history");
            execute(connection, "CREATE INDEX idx_history_identity_time "
                + "ON item_history (code, item_uuid, timestamp, id)");
            connection.commit();
            SQLException failure = assertThrows(SQLException.class,
                () -> new MySqlSchemaManager().validateHistoryIndex(connection),
                "an ascending index where the query needs descending must be refused");
            assertTrue(failure.getMessage().contains("Incompatible history index"),
                "unexpected message: " + failure.getMessage());
        }
    }

    @Test
    @DisplayName("a preview index (prefix key parts) is rejected")
    void prefixIndexIsRejected() throws Exception {
        try (Connection connection = freshSchema()) {
            execute(connection, "DROP INDEX idx_history_identity_time ON item_history");
            execute(connection, "CREATE INDEX idx_history_identity_time "
                + "ON item_history (code(4), item_uuid, timestamp DESC, id DESC)");
            connection.commit();
            assertThrows(SQLException.class,
                () -> new MySqlSchemaManager().validateHistoryIndex(connection),
                "a prefix key part is not the index the migration validated");
        }
    }

    @Test
    @DisplayName("a future schema version is refused without being downgraded")
    void futureSchemaVersionIsRefused() throws Exception {
        try (Connection connection = freshSchema()) {
            execute(connection, "UPDATE plugin_stats SET schema_version = 99 WHERE id = 1");
            connection.commit();
            assertThrows(SQLException.class,
                () -> new MySqlSchemaManager().initialize(connection));
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                     "SELECT schema_version FROM plugin_stats WHERE id = 1")) {
                rows.next();
                assertEquals(99, rows.getInt(1), "the version must not be silently rewritten");
            }
        }
    }
}
