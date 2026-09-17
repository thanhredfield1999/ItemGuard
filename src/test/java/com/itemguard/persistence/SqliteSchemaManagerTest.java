package com.itemguard.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteSchemaManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void freshDatabaseCreatesCurrentSchemaAndObservationLedger() throws Exception {
        Path database = tempDir.resolve("fresh.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.setAutoCommit(false);

            new SqliteSchemaManager().initialize(connection);

            assertEquals(SqliteSchemaManager.CURRENT_SCHEMA_VERSION, schemaVersion(connection));
            assertTrue(tableExists(connection, "tracked_items"));
            assertTrue(tableExists(connection, "item_history"));
            assertTrue(tableExists(connection, "item_observations"));
            assertTrue(tableExists(connection, "item_search_requests"));
            assertTrue(tableExists(connection, "item_snapshots"));
            assertTrue(tableExists(connection, "reclaim_claims"));
            assertTrue(tableExists(connection, "tag_publications"));
            assertTrue(tableExists(connection, "duplicate_findings"));
            assertTrue(indexIsUnique(connection, "idx_item_uuid_unique"));
            SqliteHistoryMigrationTest.assertHistoryIndex(connection);
        }
    }

    @Test
    void versionSixDatabaseAddsDuplicateFindingAuditWithoutLosingCanonicalItem()
        throws Exception {
        Path database = tempDir.resolve("version-six.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.setAutoCommit(false);
            createVersionSixSchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    INSERT INTO tracked_items
                    (code, item_uuid, created_at, last_seen_at)
                    VALUES ('AB12CD', '00000000-0000-0000-0000-000000000001', 10, 20)
                    """);
                connection.commit();
            }

            new SqliteSchemaManager().initialize(connection);

            assertEquals(SqliteSchemaManager.CURRENT_SCHEMA_VERSION, schemaVersion(connection));
            assertEquals(1, countRows(connection, "tracked_items"));
            assertTrue(tableExists(connection, "duplicate_findings"));
            assertTrue(indexIsUnique(connection, "idx_duplicate_finding_identity_epoch"));
            SqliteHistoryMigrationTest.assertHistoryIndex(connection);
        }
    }

    @Test
    void futureSchemaIsRejectedWithoutMutation() throws Exception {
        Path database = tempDir.resolve("future.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE plugin_stats (
                        id INTEGER PRIMARY KEY,
                        schema_version INT NOT NULL,
                        duplicates_detected INT DEFAULT 0,
                        last_updated BIGINT
                    )
                    """);
                statement.executeUpdate("""
                    INSERT INTO plugin_stats
                    (id, schema_version, duplicates_detected, last_updated)
                    VALUES (1, 99, 0, 0)
                    """);
                connection.commit();
            }

            assertThrows(SQLException.class, () -> new SqliteSchemaManager().initialize(connection));

            assertEquals(99, schemaVersion(connection));
            assertFalse(tableExists(connection, "item_observations"));
        }
    }

    @Test
    void versionOneDatabaseMigratesWithoutLosingTrackedItems() throws Exception {
        Path database = tempDir.resolve("version-one.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.setAutoCommit(false);
            createVersionOneSchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    INSERT INTO tracked_items
                    (code, item_uuid, created_at, last_seen_at)
                    VALUES ('AB12CD', '00000000-0000-0000-0000-000000000001', 10, 20)
                    """);
                connection.commit();
            }

            new SqliteSchemaManager().initialize(connection);

            assertEquals(SqliteSchemaManager.CURRENT_SCHEMA_VERSION, schemaVersion(connection));
            assertEquals(1, countRows(connection, "tracked_items"));
            assertTrue(tableExists(connection, "item_observations"));
            assertTrue(indexIsUnique(connection, "idx_item_uuid_unique"));
            SqliteHistoryMigrationTest.assertHistoryIndex(connection);
        }
    }

    @Test
    void duplicateLegacyIdentityRejectsMigrationWithoutDeletingData() throws Exception {
        Path database = tempDir.resolve("duplicate-legacy.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.setAutoCommit(false);
            createVersionOneSchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    INSERT INTO tracked_items
                    (code, item_uuid, created_at, last_seen_at)
                    VALUES
                    ('AB12CD', '00000000-0000-0000-0000-000000000001', 10, 20),
                    ('EF34GH', '00000000-0000-0000-0000-000000000001', 11, 21)
                    """);
                connection.commit();
            }

            assertThrows(SQLException.class, () -> new SqliteSchemaManager().initialize(connection));

            assertEquals(1, schemaVersion(connection));
            assertEquals(2, countRows(connection, "tracked_items"));
            assertFalse(tableExists(connection, "item_observations"));
        }
    }

    @Test
    void versionThreeDatabaseMigratesToSnapshotSchemaWithoutLosingSearchRequest() throws Exception {
        Path database = tempDir.resolve("version-three.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.setAutoCommit(false);
            new SqliteSchemaManager().initialize(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                    "UPDATE plugin_stats SET schema_version = 3 WHERE id = 1"
                );
                statement.executeUpdate("""
                    INSERT INTO item_search_requests
                    (code, mode, state, actor_name, created_at, expires_at, updated_at)
                    VALUES ('AB12CD', 'FIND', 'ACTIVE', 'Admin', 10, 20, 10)
                    """);
                statement.execute("DROP TABLE item_snapshots");
                connection.commit();
            }

            new SqliteSchemaManager().initialize(connection);

            assertEquals(SqliteSchemaManager.CURRENT_SCHEMA_VERSION, schemaVersion(connection));
            assertTrue(tableExists(connection, "item_snapshots"));
            assertEquals(1, countRows(connection, "item_search_requests"));
        }
    }

    private void createVersionOneSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE tracked_items (
                    code VARCHAR(16) PRIMARY KEY,
                    item_uuid VARCHAR(36) NOT NULL,
                    owner_uuid VARCHAR(36),
                    owner_name VARCHAR(255),
                    material VARCHAR(64),
                    item_name VARCHAR(255),
                    item_lore TEXT,
                    created_at BIGINT NOT NULL,
                    last_seen_at BIGINT NOT NULL,
                    last_action VARCHAR(32),
                    detection_count INT DEFAULT 0,
                    last_location TEXT
                )
                """);
            statement.execute("""
                CREATE TABLE plugin_stats (
                    id INTEGER PRIMARY KEY,
                    schema_version INT NOT NULL,
                    duplicates_detected INT DEFAULT 0,
                    last_updated BIGINT
                )
                """);
            statement.executeUpdate("""
                INSERT INTO plugin_stats
                (id, schema_version, duplicates_detected, last_updated)
                VALUES (1, 1, 0, 0)
                """);
            connection.commit();
        }
    }

    private void createVersionSixSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE tracked_items (
                    code VARCHAR(16) PRIMARY KEY,
                    item_uuid VARCHAR(36) NOT NULL,
                    owner_uuid VARCHAR(36),
                    owner_name VARCHAR(255),
                    material VARCHAR(64),
                    item_name VARCHAR(255),
                    item_lore TEXT,
                    created_at BIGINT NOT NULL,
                    last_seen_at BIGINT NOT NULL,
                    last_action VARCHAR(32),
                    detection_count INT DEFAULT 0,
                    last_location TEXT
                )
                """);
            statement.execute("""
                CREATE TABLE plugin_stats (
                    id INTEGER PRIMARY KEY,
                    schema_version INT NOT NULL,
                    duplicates_detected INT DEFAULT 0,
                    last_updated BIGINT
                )
                """);
            statement.executeUpdate("""
                INSERT INTO plugin_stats
                (id, schema_version, duplicates_detected, last_updated)
                VALUES (1, 6, 0, 0)
                """);
            connection.commit();
        }
    }

    private int countRows(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private int schemaVersion(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT schema_version FROM plugin_stats WHERE id = 1")) {
            result.next();
            return result.getInt(1);
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        try (var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean indexIsUnique(Connection connection, String index) throws Exception {
        try (var statement = connection.prepareStatement(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?")) {
            statement.setString(1, index);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getString(1).contains("UNIQUE INDEX");
            }
        }
    }
}
