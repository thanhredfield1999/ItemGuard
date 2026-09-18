package com.itemguard.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The MySQL 8 schema for the Premium backend, and the portability rules that make it one.
 *
 * <p>Design: {@code docs/design/2026-09-16-premium-mysql-contract.md} (M1). This class exists
 * because the port is <em>not</em> a driver swap: SQLite gives three things this schema depends on
 * and MySQL does not, and each one is re-expressed rather than translated.
 *
 * <ol>
 *   <li><b>Partial unique indexes have no MySQL equivalent.</b> SQLite enforces "one active
 *       reclaim claim per identity" and "one in-flight publication per physical source" with
 *       {@code UNIQUE ... WHERE state IN (...)}. Here each becomes a {@code STORED} generated
 *       column that evaluates to the key while the row is in a blocking state and to
 *       {@code NULL} otherwise, plus a plain unique index. MySQL does not constrain
 *       {@code NULL}s, so the index is exactly as selective as the partial one it replaces —
 *       and the invariant itself is asserted by tests that run on both backends.</li>
 *   <li><b>Text comparison is not byte comparison by default.</b> SQLite compares {@code TEXT}
 *       with {@code BINARY} unless told otherwise. MySQL's default collation for
 *       {@code utf8mb4} is case- <em>and</em> accent-insensitive: on the default, the codes
 *       {@code AB12CD} and {@code ab12cd} would be the same identity, and {@code "AB12CD "}
 *       would equal {@code "AB12CD"}. Every table is therefore created with
 *       {@code COLLATE utf8mb4_0900_bin}, which is {@code NO PAD} as well — the same
 *       comparison SQLite performs, including the trailing-space case.</li>
 *   <li><b>{@code BLOB} is not unbounded here.</b> SQLite stores any payload length;
 *       MySQL's {@code BLOB} stops at 65,535 bytes while the snapshot codec is configured with
 *       a 1 MiB maximum, so a snapshot between those two numbers would be stored by SQLite and
 *       rejected — or worse, truncated under a relaxed {@code sql_mode} — by MySQL. Snapshot
 *       payload columns are {@code MEDIUMBLOB}.</li>
 * </ol>
 *
 * <p><b>What deliberately does not carry over from the SQLite manager.</b> Two guarantees are
 * lost, and are stated here rather than implied:
 *
 * <ul>
 *   <li><b>Index build budget.</b> {@link SqliteSchemaManager} aborts a history-index build that
 *       exceeds a bounded number of progress callbacks, using the driver's
 *       {@code ProgressHandler}. MySQL offers no way for a client to interrupt its own DDL, so
 *       that guard is SQLite-only. A large MySQL table therefore pays the build once, with no
 *       plugin-side ceiling.</li>
 *   <li><b>Rollback of a failed initialization.</b> In MySQL, DDL commits implicitly; the
 *       transaction this method requires cannot be rolled back around it. Re-running is safe
 *       ({@code CREATE TABLE/INDEX IF NOT EXISTS}, idempotent stats seeding) and version
 *       validation runs first, but a failure halfway through leaves a partial schema. That is a
 *       property of the backend, not of this code.</li>
 * </ul>
 *
 * <p>Session state is verified rather than assumed: durability and strictness are read from the
 * server before anything is created, and a server that cannot state them is refused by name
 * (see {@link #requireDurableSession(Connection)}).
 */
public final class MySqlSchemaManager {

    /**
     * The schema version this manager produces.
     *
     * <p>Deliberately <strong>two ahead</strong> of {@link SqliteSchemaManager#CURRENT_SCHEMA_VERSION}
     * (8), and each step is a decision that is stated rather than inherited:
     *
     * <ol>
     *   <li><b>v9 — {@code server_id}.</b> The column exists only where several servers share one
     *       database, and a single-server SQLite install has nothing to record in it.</li>
     *   <li><b>v10 — finding acknowledgement.</b> {@code duplicate_findings} carries who marked a
     *       finding read. It is MySQL-only for an interoperability reason, not a preference: the
     *       shipped LITE jar opens a SQLite file at version 8 and refuses a database whose recorded
     *       version is newer (correctly — it cannot validate columns it does not know). Raising the
     *       SQLite ladder here would make a database written by this build unopenable by the LITE
     *       jar that is already published, so the column stays on the backend where Premium lives.
     *       On SQLite, {@code /finditem readdupe} answers that acknowledgement needs the MySQL
     *       backend instead of pretending a write happened.</li>
     * </ol>
     */
    public static final int CURRENT_SCHEMA_VERSION = 10;

    /** MySQL 8.0 is the floor: {@code utf8mb4_0900_bin} and real descending indexes are 8.0 features. */
    public static final int MINIMUM_SERVER_MAJOR_VERSION = 8;

    static final String COLLATION = "utf8mb4_0900_bin";

    /**
     * Every table this schema owns, in one place: {@link #validateExistingSchema(Connection)} uses
     * the list to tell a partial restore from a fresh install.
     *
     * <p>If a later migration adds a table, its table set is this list plus what it adds, and this
     * check has to be extended for that version — not weakened to make a half-restored database
     * pass again.
     */
    static final List<String> OWNED_TABLES = List.of(
        "tracked_items",
        "item_history",
        "item_observations",
        "duplicate_findings",
        "item_search_requests",
        "item_snapshots",
        "reclaim_claims",
        "tag_publications",
        "plugin_stats"
    );

    /**
     * The strictness this schema requires. Non-strict mode silently truncates an oversized value
     * instead of rejecting it, which for an audit table is the same as losing evidence.
     *
     * <p>{@code STRICT_TRANS_TABLES} is accepted as well as {@code STRICT_ALL_TABLES}: the
     * difference between them is non-transactional tables, and this schema creates none. MySQL
     * 8.4 ships with {@code STRICT_TRANS_TABLES} by default, so demanding the other spelling
     * would refuse every default installation for a distinction that cannot apply here.
     */
    static final String[] REQUIRED_SQL_MODE = {
        "NO_ENGINE_SUBSTITUTION"
    };

    static final String[] STRICT_SQL_MODES = {
        "STRICT_ALL_TABLES", "STRICT_TRANS_TABLES"
    };

    public void initialize(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Schema initialization requires a transaction");
        validateExistingSchema(connection);
        requireDurableSession(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS tracked_items (
                    code VARCHAR(16) NOT NULL PRIMARY KEY,
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
                    last_location TEXT,
                    UNIQUE KEY idx_item_uuid_unique (item_uuid)
                    ,FULLTEXT KEY idx_tracked_items_catalog_fulltext (code, item_name, material) WITH PARSER ngram
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=%s
                """.formatted(COLLATION));
            statement.execute("""
                CREATE TABLE IF NOT EXISTS item_history (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
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
                    additional_data TEXT,
                    server_id VARCHAR(64) NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=%s
                """.formatted(COLLATION));
            statement.execute("""
                CREATE TABLE IF NOT EXISTS item_observations (
                    observation_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    item_uuid VARCHAR(36) NOT NULL,
                    code VARCHAR(16) NOT NULL,
                    scan_epoch BIGINT NOT NULL,
                    epoch_complete INT NOT NULL DEFAULT 0,
                    holder_type VARCHAR(32) NOT NULL,
                    holder_id VARCHAR(255) NOT NULL,
                    slot INT NOT NULL,
                    observed_at BIGINT NOT NULL,
                    server_id VARCHAR(64) NOT NULL,
                    UNIQUE KEY idx_observation_slot_unique
                        (scan_epoch, holder_type, holder_id, slot, item_uuid),
                    KEY idx_observation_identity_epoch (item_uuid, scan_epoch),
                    -- The cross-server question is "which servers has this identity been seen on
                    -- inside a window", so the index carries the server and the time.
                    KEY idx_observation_identity_server (item_uuid, server_id, observed_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=%s
                """.formatted(COLLATION));
            statement.execute("""
                CREATE TABLE IF NOT EXISTS duplicate_findings (
                    finding_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    item_uuid VARCHAR(36) NOT NULL,
                    code VARCHAR(16) NOT NULL,
                    scan_epoch BIGINT NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    distinct_locations INT NOT NULL,
                    action VARCHAR(16) NOT NULL,
                    created_at BIGINT NOT NULL,
                    detail TEXT,
                    -- v10: who marked this finding read, and when. NULL means unread, which is the
                    -- only state that carries a triage duty.
                    acknowledged_at BIGINT NULL,
                    acknowledged_by VARCHAR(64) NULL,
                    UNIQUE KEY idx_duplicate_finding_identity_epoch (item_uuid, scan_epoch),
                    KEY idx_duplicate_finding_identity_created (item_uuid, created_at),
                    CONSTRAINT fk_finding_item FOREIGN KEY (code) REFERENCES tracked_items (code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=%s
                """.formatted(COLLATION));
            statement.execute("""
                CREATE TABLE IF NOT EXISTS item_search_requests (
                    code VARCHAR(16) NOT NULL PRIMARY KEY,
                    mode VARCHAR(16) NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    actor_uuid VARCHAR(36),
                    actor_name VARCHAR(255) NOT NULL,
                    created_at BIGINT NOT NULL,
                    expires_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    KEY idx_search_state_expiry (state, expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=%s
                """.formatted(COLLATION));
            statement.execute("""
                CREATE TABLE IF NOT EXISTS item_snapshots (
                    code VARCHAR(16) NOT NULL PRIMARY KEY,
                    snapshot_version INT NOT NULL,
                    payload MEDIUMBLOB NOT NULL,
                    sha256 BLOB NOT NULL,
                    captured_at BIGINT NOT NULL,
                    CONSTRAINT fk_snapshot_item FOREIGN KEY (code) REFERENCES tracked_items (code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=%s
                """.formatted(COLLATION));
            statement.execute("""
                CREATE TABLE IF NOT EXISTS reclaim_claims (
                    claim_id VARCHAR(36) NOT NULL PRIMARY KEY,
                    idempotency_key VARCHAR(128) NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    code VARCHAR(16) NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    requested_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    detail TEXT,
                    active_identity_lock VARCHAR(64) COLLATE utf8mb4_0900_bin
                        GENERATED ALWAYS AS (
                            CASE WHEN state IN ('PENDING', 'PREPARED', 'COMMITTED')
                                 THEN CONCAT(player_uuid, ':', code) END
                        ) STORED,
                    UNIQUE KEY idx_claim_idempotency_unique (idempotency_key),
                    -- Replaces SQLite's "CREATE UNIQUE INDEX ... WHERE state IN (...)".
                    UNIQUE KEY idx_reclaim_identity_lock (active_identity_lock),
                    CONSTRAINT fk_claim_item FOREIGN KEY (code) REFERENCES tracked_items (code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=%s
                """.formatted(COLLATION));
            statement.execute("""
                CREATE TABLE IF NOT EXISTS tag_publications (
                    publication_id VARCHAR(36) NOT NULL PRIMARY KEY,
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
                    payload MEDIUMBLOB NOT NULL,
                    sha256 BLOB NOT NULL,
                    captured_at BIGINT NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    detail TEXT,
                    server_id VARCHAR(64) NOT NULL,
                    prepared_source_lock VARCHAR(255) COLLATE utf8mb4_0900_bin
                        GENERATED ALWAYS AS (
                            CASE WHEN state = 'PREPARED' THEN source_key END
                        ) STORED,
                    -- Replaces SQLite's "CREATE UNIQUE INDEX ... WHERE state = 'PREPARED'".
                    UNIQUE KEY idx_tag_publication_source_lock (prepared_source_lock),
                    UNIQUE KEY idx_tag_publication_code_unique (code),
                    UNIQUE KEY idx_tag_publication_uuid_unique (item_uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=%s
                """.formatted(COLLATION));
            statement.execute("""
                CREATE TABLE IF NOT EXISTS plugin_stats (
                    id INT NOT NULL PRIMARY KEY,
                    schema_version INT NOT NULL,
                    duplicates_detected INT DEFAULT 0,
                    last_updated BIGINT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=%s
                """.formatted(COLLATION));
            createHistoryIndex(connection, statement);
            validateHistoryIndex(connection);
            ensureFindingAcknowledgementColumns(connection, statement);
            statement.executeUpdate("""
                INSERT IGNORE INTO plugin_stats
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

    /**
     * Refuses a session whose durability or strictness the server will not state.
     *
     * <p>{@code PRAGMA synchronous = FULL} is the plugin's own decision; the MySQL equivalent,
     * {@code innodb_flush_log_at_trx_commit}, belongs to the server. Reading it and refusing is
     * the only honest option: a plugin that cannot establish it is guessing about the last
     * transaction, and setting it for its own session would make the read self-fulfilling.
     */
    void requireDurableSession(Connection connection) throws SQLException {
        String version = queryString(connection, "SELECT VERSION()");
        if (version.toUpperCase(Locale.ROOT).contains("MARIADB")) {
            throw new SQLException(
                "Refusing to start: this server reports itself as MariaDB (" + version + "). "
                    + "ItemGuard's MySQL schema relies on utf8mb4_0900_bin and on stored "
                    + "generated columns backed by unique keys, neither of which MariaDB "
                    + "provides; it is verified against MySQL 8.x."
            );
        }
        int major = majorVersionOf(version);
        if (major < MINIMUM_SERVER_MAJOR_VERSION) {
            throw new SQLException(
                "ItemGuard's MySQL schema requires MySQL "
                    + MINIMUM_SERVER_MAJOR_VERSION + ".0 or newer (binary collation, descending "
                    + "indexes); this server reports " + version
            );
        }
        long flush = queryLong(connection, "SELECT @@innodb_flush_log_at_trx_commit");
        if (flush != 1) {
            throw new SQLException(
                "Refusing to start: innodb_flush_log_at_trx_commit is " + flush + ", not 1. "
                    + "With a shared database ItemGuard cannot guarantee that an identity it "
                    + "reported as written survives a crash unless the server flushes every "
                    + "transaction to disk. Set innodb_flush_log_at_trx_commit = 1 in the server "
                    + "configuration and restart it."
            );
        }
        String sqlMode = queryString(connection, "SELECT @@sql_mode").toUpperCase(Locale.ROOT);
        boolean strict = false;
        for (String candidate : STRICT_SQL_MODES) {
            if (sqlMode.contains(candidate)) strict = true;
        }
        if (!strict) {
            throw new SQLException(
                "Refusing to start: sql_mode is neither "
                    + String.join(" nor ", STRICT_SQL_MODES) + " (server mode: " + sqlMode + "). "
                    + "In a non-strict mode MySQL truncates an oversized value instead of "
                    + "rejecting it, which would silently lose the evidence this plugin exists "
                    + "to keep."
            );
        }
        for (String required : REQUIRED_SQL_MODE) {
            if (!sqlMode.contains(required)) {
                throw new SQLException(
                    "Refusing to start: sql_mode does not include " + required + " (server mode: "
                        + sqlMode + "). Without it MySQL may substitute a different storage "
                        + "engine for a table this schema defines as InnoDB."
                );
            }
        }
    }

    /**
     * Adds the v10 acknowledgement columns to a database created at v9.
     *
     * <p>MySQL has no {@code ADD COLUMN IF NOT EXISTS}, so the existing columns are looked up first —
     * the same shape {@link #createHistoryIndex} uses. Both columns are nullable with no default, so
     * the ALTER is a metadata-only change on InnoDB and existing rows stay unread, which is the right
     * starting state: a finding nobody has triaged.
     */
    private void ensureFindingAcknowledgementColumns(Connection connection, Statement statement)
        throws SQLException {
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (var lookup = connection.prepareStatement("""
                SELECT COLUMN_NAME FROM information_schema.columns
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'duplicate_findings'
                  AND COLUMN_NAME IN ('acknowledged_at', 'acknowledged_by')
                """)) {
            try (ResultSet rows = lookup.executeQuery()) {
                while (rows.next()) {
                    existing.add(rows.getString(1));
                }
            }
        }
        if (!existing.contains("acknowledged_at")) {
            statement.execute(
                "ALTER TABLE duplicate_findings ADD COLUMN acknowledged_at BIGINT NULL"
            );
        }
        if (!existing.contains("acknowledged_by")) {
            statement.execute(
                "ALTER TABLE duplicate_findings ADD COLUMN acknowledged_by VARCHAR(64) NULL"
            );
        }
    }

    private void createHistoryIndex(Connection connection, Statement statement) throws SQLException {
        // MySQL has no `CREATE INDEX IF NOT EXISTS` (that is MariaDB syntax), so the existing
        // index is looked up first. Two servers racing here is a fail-closed outcome by design:
        // the second one gets a duplicate-key error rather than a schema it did not verify.
        if (historyIndexExists(connection)) return;
        statement.execute("""
            CREATE INDEX idx_history_identity_time
            ON item_history (code, item_uuid, timestamp DESC, id DESC)
            """);
    }

    private boolean historyIndexExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM information_schema.statistics
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item_history'
                  AND INDEX_NAME = 'idx_history_identity_time'
                LIMIT 1
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * The MySQL counterpart of the SQLite {@code PRAGMA index_list}/{@code index_xinfo} check:
     * the index must be non-unique, BTREE, without prefix or expression parts, over exactly
     * {@code (code, item_uuid, timestamp DESC, id DESC)}, with both identity columns compared
     * byte-wise.
     */
    void validateHistoryIndex(Connection connection) throws SQLException {
        String[] columns = {"code", "item_uuid", "timestamp", "id"};
        String[] descending = {"A", "A", "D", "D"};
        int position = 0;
        try (var statement = connection.prepareStatement("""
                SELECT SEQ_IN_INDEX, COLUMN_NAME, COLLATION, SUB_PART, NON_UNIQUE, INDEX_TYPE,
                       EXPRESSION
                FROM information_schema.statistics
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item_history'
                  AND INDEX_NAME = 'idx_history_identity_time'
                ORDER BY SEQ_IN_INDEX
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (position >= columns.length
                        || !columns[position].equals(rows.getString("COLUMN_NAME"))
                        || !descending[position].equals(rows.getString("COLLATION"))
                        || rows.getObject("SUB_PART") != null
                        || rows.getObject("EXPRESSION") != null
                        || rows.getString("INDEX_TYPE") == null
                        || !"BTREE".equalsIgnoreCase(rows.getString("INDEX_TYPE"))
                        || rows.getInt("NON_UNIQUE") != 1) {
                        throw new SQLException("Incompatible history index definition");
                    }
                    position++;
                }
            }
        }
        if (position == 0) throw new SQLException("Missing or incompatible history index");
        if (position != columns.length) throw new SQLException("Incomplete history index definition");
        try (var statement = connection.prepareStatement("""
                SELECT COLUMN_NAME, COLLATION_NAME
                FROM information_schema.columns
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item_history'
                  AND COLUMN_NAME IN ('code', 'item_uuid')
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                int checked = 0;
                while (rows.next()) {
                    if (!COLLATION.equals(rows.getString("COLLATION_NAME"))) {
                        throw new SQLException(
                            "History identity columns are not compared byte-wise: "
                                + rows.getString("COLUMN_NAME") + " uses "
                                + rows.getString("COLLATION_NAME")
                        );
                    }
                    checked++;
                }
                if (checked != 2) throw new SQLException("History identity columns are missing");
            }
        }
    }

    private void validateExistingSchema(Connection connection) throws SQLException {
        try (var metadata = connection.prepareStatement("""
                SELECT 1 FROM information_schema.tables
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plugin_stats'
                """);
             ResultSet table = metadata.executeQuery()) {
            if (!table.next()) {
                return;
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT schema_version FROM plugin_stats WHERE id = 1")) {
            if (!result.next()) {
                throw new SQLException("ItemGuard schema metadata is missing row id=1");
            }
            int schemaVersion = result.getInt(1);
            if (result.wasNull() || schemaVersion < 1) {
                throw new SQLException("Invalid ItemGuard schema version metadata");
            }
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw new SQLException(
                    "Unsupported future ItemGuard schema version: " + schemaVersion
                );
            }
        }
        /*
         * An existing schema must be complete.
         *
         * Measured before this check existed (2026-09-18, phase C of the controlled backup/restore
         * gate): a dump restored without item_history was accepted, the table was re-created empty
         * by the DDL below, the plugin enabled and logged nothing. The item identity survived and
         * its entire audit trail was gone, so the plugin reported a healthy database whose history
         * had been half-restored — the one state an operator cannot see from the plugin's output.
         *
         * Refusing is the only honest option: re-creating an empty table replaces missing audit
         * rows with nothing, and the plugin exists to keep that evidence. A database with no
         * ItemGuard schema at all is still a first install and is created from scratch.
         */
        Set<String> present = new HashSet<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(OWNED_TABLES.size(), "?"));
        try (var statement = connection.prepareStatement(
            "SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (" + placeholders + ")")) {
            int index = 1;
            for (String table : OWNED_TABLES) {
                statement.setString(index++, table);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    present.add(rows.getString(1));
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String table : OWNED_TABLES) {
            if (!present.contains(table)) {
                missing.add(table);
            }
        }
        if (!missing.isEmpty()) {
            throw new SQLException(
                "Refusing to start: this database carries ItemGuard's schema metadata but is "
                    + "missing " + String.join(", ", missing) + ". A restore that lost tables is not "
                    + "repaired by re-creating them empty — that would silently drop the history "
                    + "those tables held. Restore the full dump again (or drop this schema to start "
                    + "fresh) and start ItemGuard once it is complete."
            );
        }
    }

    /**
     * The leading integer of a version string: {@code "8.4.6"} and {@code "8.4.6-log"} are both
     * major version 8. A version with no leading number is unknown, not modern.
     */
    static int majorVersionOf(String version) {
        int index = 0;
        while (index < version.length() && !Character.isDigit(version.charAt(index))) index++;
        int start = index;
        while (index < version.length() && Character.isDigit(version.charAt(index))) index++;
        if (start == index) return 0;
        try {
            return Integer.parseInt(version.substring(start, index));
        } catch (NumberFormatException overflow) {
            return Integer.MAX_VALUE;
        }
    }

    private long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("No result for: " + sql);
            return rows.getLong(1);
        }
    }

    private String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("No result for: " + sql);
            String value = rows.getString(1);
            return value == null ? "" : value;
        }
    }
}
