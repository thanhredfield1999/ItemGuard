package com.itemguard.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteSchemaManager {

    public static final int CURRENT_SCHEMA_VERSION = 8;
    private final int indexProgressLimit;
    private final java.util.function.LongSupplier clock;

    public SqliteSchemaManager() { this(100_000, System::nanoTime); }

    SqliteSchemaManager(int indexProgressLimit, java.util.function.LongSupplier clock) {
        this.indexProgressLimit = indexProgressLimit;
        this.clock = clock;
    }

    public void initialize(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Schema initialization requires a transaction");
        validateExistingSchema(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS tracked_items (
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
                CREATE UNIQUE INDEX IF NOT EXISTS idx_item_uuid_unique
                ON tracked_items(item_uuid)
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS item_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code VARCHAR(16) NOT NULL,
                    item_uuid VARCHAR(36) NOT NULL,
                    action VARCHAR(32) NOT NULL,
                    player_name VARCHAR(255),
                    player_uuid VARCHAR(36),
                    location TEXT,
                    world VARCHAR(64),
                    x INT,
                    y INT,
                    z INT,
                    timestamp BIGINT NOT NULL,
                    additional_data TEXT
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS item_observations (
                    observation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_uuid VARCHAR(36) NOT NULL,
                    code VARCHAR(16) NOT NULL,
                    scan_epoch BIGINT NOT NULL,
                    epoch_complete INT NOT NULL DEFAULT 0,
                    holder_type VARCHAR(32) NOT NULL,
                    holder_id VARCHAR(255) NOT NULL,
                    slot INT NOT NULL,
                    observed_at BIGINT NOT NULL,
                    UNIQUE(scan_epoch, holder_type, holder_id, slot, item_uuid)
                )
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_observation_identity_epoch
                ON item_observations(item_uuid, scan_epoch)
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS duplicate_findings (
                    finding_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_uuid VARCHAR(36) NOT NULL,
                    code VARCHAR(16) NOT NULL,
                    scan_epoch BIGINT NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    distinct_locations INT NOT NULL,
                    action VARCHAR(16) NOT NULL,
                    created_at BIGINT NOT NULL,
                    detail TEXT,
                    FOREIGN KEY(code) REFERENCES tracked_items(code)
                )
                """);
            statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_duplicate_finding_identity_epoch
                ON duplicate_findings(item_uuid, scan_epoch)
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_duplicate_finding_identity_created
                ON duplicate_findings(item_uuid, created_at)
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS item_search_requests (
                    code VARCHAR(16) PRIMARY KEY,
                    mode VARCHAR(16) NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    actor_uuid VARCHAR(36),
                    actor_name VARCHAR(255) NOT NULL,
                    created_at BIGINT NOT NULL,
                    expires_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_search_state_expiry
                ON item_search_requests(state, expires_at)
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS item_snapshots (
                    code VARCHAR(16) PRIMARY KEY,
                    snapshot_version INT NOT NULL,
                    payload BLOB NOT NULL,
                    sha256 BLOB NOT NULL,
                    captured_at BIGINT NOT NULL,
                    FOREIGN KEY(code) REFERENCES tracked_items(code)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS reclaim_claims (
                    claim_id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
                    player_uuid VARCHAR(36) NOT NULL,
                    code VARCHAR(16) NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    requested_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    detail TEXT,
                    FOREIGN KEY(code) REFERENCES tracked_items(code)
                )
                """);
            statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_reclaim_identity_lock
                ON reclaim_claims(player_uuid, code)
                WHERE state IN ('PENDING', 'PREPARED', 'COMMITTED')
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS tag_publications (
                    publication_id VARCHAR(36) PRIMARY KEY,
                    source_key VARCHAR(255) NOT NULL,
                    source_digest BLOB NOT NULL,
                    code VARCHAR(16) NOT NULL,
                    item_uuid VARCHAR(36) NOT NULL,
                    owner_uuid VARCHAR(36),
                    owner_name VARCHAR(255),
                    material VARCHAR(64),
                    item_name VARCHAR(255),
                    item_lore TEXT,
                    created_item_at BIGINT NOT NULL,
                    last_seen_at BIGINT NOT NULL,
                    last_action VARCHAR(32),
                    detection_count INT NOT NULL,
                    last_location TEXT,
                    snapshot_version INT NOT NULL,
                    payload BLOB NOT NULL,
                    sha256 BLOB NOT NULL,
                    captured_at BIGINT NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    detail TEXT
                )
                """);
            statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_tag_publication_source_lock
                ON tag_publications(source_key)
                WHERE state = 'PREPARED'
                """);
            statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_tag_publication_code_unique
                ON tag_publications(code)
                """);
            statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_tag_publication_uuid_unique
                ON tag_publications(item_uuid)
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS plugin_stats (
                    id INTEGER PRIMARY KEY,
                    schema_version INT NOT NULL,
                    duplicates_detected INT DEFAULT 0,
                    last_updated BIGINT
                )
                """);
            createHistoryIndex(connection, statement);
            validateHistoryIndex(connection);
            statement.executeUpdate("""
                INSERT OR IGNORE INTO plugin_stats
                (id, schema_version, duplicates_detected, last_updated)
                VALUES (1, %d, 0, 0)
                """.formatted(CURRENT_SCHEMA_VERSION));
            statement.executeUpdate("""
                UPDATE plugin_stats
                SET schema_version = %d
                WHERE id = 1 AND schema_version < %d
                """.formatted(CURRENT_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION));
            connection.commit();
        } catch (SQLException exception) {
            try { connection.rollback(); }
            catch (SQLException rollbackFailure) { exception.addSuppressed(rollbackFailure); }
            throw exception;
        }
    }

    private void createHistoryIndex(Connection connection, Statement statement) throws SQLException {
        var sqlite = connection.unwrap(org.sqlite.SQLiteConnection.class);
        long started = clock.getAsLong();
        var budget = new org.sqlite.ProgressHandler() {
            private int calls;
            private boolean aborted;
            @Override protected int progress() {
                aborted = ++calls >= indexProgressLimit || clock.getAsLong() - started > 30_000_000_000L;
                return aborted ? 1 : 0;
            }
        };
        org.sqlite.ProgressHandler.setHandler(sqlite, 1000, budget);
        SQLException original = null;
        try {
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_history_identity_time
                ON item_history(code,item_uuid,timestamp DESC,id DESC)
                """);
        } catch (SQLException failure) {
            original = budget.aborted ? new SQLException(
                "ItemGuard history index migration budget exceeded (checks=" + budget.calls
                    + ", elapsed_ms=" + (clock.getAsLong() - started) / 1_000_000L
                    + "); startup denied. Keep the database and use a reviewed maintenance migration; do not downgrade its version.",
                failure) : failure;
            throw original;
        } finally {
            try { org.sqlite.ProgressHandler.clearHandler(sqlite); }
            catch (SQLException cleanupFailure) {
                if (original != null) original.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private void validateHistoryIndex(Connection connection) throws SQLException {
        boolean found = false;
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA index_list('item_history')")) {
            while (rows.next()) {
                if (!"idx_history_identity_time".equals(rows.getString("name"))) continue;
                found = rows.getInt("unique") == 0 && rows.getInt("partial") == 0;
            }
        }
        if (!found) throw new SQLException("Missing or incompatible history index");
        String[] columns = {"code", "item_uuid", "timestamp", "id"};
        int[] descending = {0, 0, 1, 1};
        int position = 0;
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA index_xinfo('idx_history_identity_time')")) {
            while (rows.next()) {
                if (rows.getInt("key") == 0) continue;
                if (position >= columns.length || !columns[position].equals(rows.getString("name"))
                    || rows.getInt("desc") != descending[position]
                    || !"BINARY".equals(rows.getString("coll"))) {
                    throw new SQLException("Incompatible history index definition");
                }
                position++;
            }
        }
        if (position != columns.length) throw new SQLException("Incomplete history index definition");
    }

    private void validateExistingSchema(Connection connection) throws SQLException {
        try (var metadata = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'plugin_stats'");
             ResultSet table = metadata.executeQuery()) {
            if (!table.next()) {
                return;
            }
        }

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT schema_version, typeof(schema_version) FROM plugin_stats WHERE id = 1")) {
            if (!result.next()) {
                throw new SQLException("ItemGuard schema metadata is missing row id=1");
            }
            long schemaVersion = result.getLong(1);
            if (!"integer".equals(result.getString(2)) || schemaVersion < 1) {
                throw new SQLException("Invalid ItemGuard schema version metadata");
            }
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw new SQLException(
                    "Unsupported future ItemGuard schema version: " + schemaVersion
                );
            }
        }
    }
}
