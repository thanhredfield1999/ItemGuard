package com.itemguard.persistence;

import com.itemguard.multiserver.ServerIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One-way, dry-run-first migration from an existing ItemGuard SQLite database to MySQL.
 *
 * <p>The source is opened read-only and never deleted or modified. The target must be empty: this
 * avoids guessing whether a partial previous migration can be merged safely. The operation is
 * additive to the Premium backend and is not reachable until the full MySQL runtime is enabled.
 */
public final class MySqlMigrationService {
    private static final String[] TABLES = {
        "tracked_items", "item_history", "item_observations", "duplicate_findings",
        "item_search_requests", "item_snapshots", "reclaim_claims", "tag_publications",
        "plugin_stats"
    };
    private static final String[] DATA_TABLES = {
        "tracked_items", "item_history", "item_observations", "duplicate_findings",
        "item_search_requests", "item_snapshots", "reclaim_claims", "tag_publications"
    };

    private final MySqlConnectionOwner owner;
    private final Path source;
    private final String serverId;

    public MySqlMigrationService(MySqlConnectionOwner owner, Path source, String serverId) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.source = Objects.requireNonNull(source, "source").toAbsolutePath();
        this.serverId = ServerIdentity.configured(serverId).name();
    }

    public MigrationReport dryRun() {
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("SQLite source database does not exist: " + source);
        }
        try (Connection sqlite = openReadOnly()) {
            requireSupportedSource(sqlite);
            Map<String, Long> rows = sourceCounts(sqlite);
            Map<String, Long> target = owner.call(this::targetCounts);
            boolean empty = java.util.Arrays.stream(DATA_TABLES)
                .mapToLong(table -> target.getOrDefault(table, 0L))
                .allMatch(value -> value == 0L);
            return new MigrationReport(true, false, false, empty, rows, target);
        } catch (SQLException failure) {
            throw new IllegalStateException("SQLite migration dry-run failed; source was not changed", failure);
        }
    }

    public MigrationReport migrate() {
        MigrationReport plan = dryRun();
        if (!plan.targetEmpty()) {
            throw new IllegalStateException(
                "MySQL migration target is not empty; refusing merge or overwrite");
        }
        try (Connection sqlite = openReadOnly()) {
            requireSupportedSource(sqlite);
            owner.call(connection -> {
                copyAll(sqlite, connection);
                return null;
            });
            Map<String, Long> target = owner.call(this::targetCounts);
            boolean verified = plan.sourceRows().equals(target);
            if (!verified) {
                throw new IllegalStateException(
                    "MySQL migration row-count verification failed: source="
                        + plan.sourceRows() + ", target=" + target);
            }
            return new MigrationReport(false, true, true, false, plan.sourceRows(), target);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                "SQLite migration failed; source was not changed and target transaction was rolled back",
                failure);
        }
    }

    private Connection openReadOnly() throws SQLException {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setReadOnly(true);
        config.setOpenMode(org.sqlite.SQLiteOpenMode.READONLY);
        return config.createConnection("jdbc:sqlite:" + source);
    }

    private void requireSupportedSource(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "SELECT schema_version FROM plugin_stats WHERE id = 1")) {
            if (!rows.next()) throw new SQLException("SQLite schema metadata row id=1 is missing");
            int version = rows.getInt(1);
            if (version != SqliteSchemaManager.CURRENT_SCHEMA_VERSION) {
                throw new SQLException(
                    "SQLite migration requires schema version " + SqliteSchemaManager.CURRENT_SCHEMA_VERSION
                        + ", found " + version);
            }
        }
    }

    private Map<String, Long> sourceCounts(Connection connection) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) counts.put(table, count(connection, table));
        return counts;
    }

    private Map<String, Long> targetCounts(Connection connection) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) counts.put(table, count(connection, table));
        return counts;
    }

    private void copyAll(Connection sourceConnection, Connection target) throws SQLException {
        copyTrackedItems(sourceConnection, target);
        copyHistory(sourceConnection, target);
        copyObservations(sourceConnection, target);
        copyFindings(sourceConnection, target);
        copySearchRequests(sourceConnection, target);
        copySnapshots(sourceConnection, target);
        copyClaims(sourceConnection, target);
        copyPublications(sourceConnection, target);
        copyPluginStats(sourceConnection, target);
    }

    private void copyTrackedItems(Connection sourceConnection, Connection target) throws SQLException {
        transfer(sourceConnection, target,
            "SELECT code,item_uuid,owner_uuid,owner_name,material,item_name,item_lore,created_at,last_seen_at,last_action,detection_count,last_location FROM tracked_items",
            "INSERT INTO tracked_items(code,item_uuid,owner_uuid,owner_name,material,item_name,item_lore,created_at,last_seen_at,last_action,detection_count,last_location) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            (source, insert) -> {
                for (int i = 1; i <= 12; i++) insert.setObject(i, source.getObject(i));
            });
    }

    private void copyHistory(Connection sourceConnection, Connection target) throws SQLException {
        transfer(sourceConnection, target,
            "SELECT code,item_uuid,action,player_name,player_uuid,location,world,x,y,z,timestamp,additional_data FROM item_history",
            "INSERT INTO item_history(code,item_uuid,action,player_name,player_uuid,location,world,x,y,z,timestamp,additional_data,server_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (source, insert) -> {
                for (int i = 1; i <= 12; i++) insert.setObject(i, source.getObject(i));
                insert.setString(13, serverId);
            });
    }

    private void copyObservations(Connection sourceConnection, Connection target) throws SQLException {
        transfer(sourceConnection, target,
            "SELECT item_uuid,code,scan_epoch,epoch_complete,holder_type,holder_id,slot,observed_at FROM item_observations",
            "INSERT INTO item_observations(item_uuid,code,scan_epoch,epoch_complete,holder_type,holder_id,slot,observed_at,server_id) VALUES(?,?,?,?,?,?,?,?,?)",
            (source, insert) -> {
                for (int i = 1; i <= 8; i++) insert.setObject(i, source.getObject(i));
                insert.setString(9, serverId);
            });
    }

    private void copyFindings(Connection sourceConnection, Connection target) throws SQLException {
        transfer(sourceConnection, target,
            "SELECT item_uuid,code,scan_epoch,status,distinct_locations,action,created_at,detail FROM duplicate_findings",
            "INSERT INTO duplicate_findings(item_uuid,code,scan_epoch,status,distinct_locations,action,created_at,detail) VALUES(?,?,?,?,?,?,?,?)",
            (source, insert) -> {
                for (int i = 1; i <= 8; i++) insert.setObject(i, source.getObject(i));
            });
    }

    private void copySearchRequests(Connection sourceConnection, Connection target) throws SQLException {
        transfer(sourceConnection, target,
            "SELECT code,mode,state,actor_uuid,actor_name,created_at,expires_at,updated_at FROM item_search_requests",
            "INSERT INTO item_search_requests(code,mode,state,actor_uuid,actor_name,created_at,expires_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            (source, insert) -> {
                for (int i = 1; i <= 8; i++) insert.setObject(i, source.getObject(i));
            });
    }

    private void copySnapshots(Connection sourceConnection, Connection target) throws SQLException {
        transfer(sourceConnection, target,
            "SELECT code,snapshot_version,payload,sha256,captured_at FROM item_snapshots",
            "INSERT INTO item_snapshots(code,snapshot_version,payload,sha256,captured_at) VALUES(?,?,?,?,?)",
            (source, insert) -> {
                for (int i = 1; i <= 5; i++) insert.setObject(i, source.getObject(i));
            });
    }

    private void copyClaims(Connection sourceConnection, Connection target) throws SQLException {
        transfer(sourceConnection, target,
            "SELECT claim_id,idempotency_key,player_uuid,code,state,requested_at,updated_at,detail FROM reclaim_claims",
            "INSERT INTO reclaim_claims(claim_id,idempotency_key,player_uuid,code,state,requested_at,updated_at,detail) VALUES(?,?,?,?,?,?,?,?)",
            (source, insert) -> {
                for (int i = 1; i <= 8; i++) insert.setObject(i, source.getObject(i));
            });
    }

    private void copyPublications(Connection sourceConnection, Connection target) throws SQLException {
        transfer(sourceConnection, target,
            "SELECT publication_id,source_key,source_digest,code,item_uuid,owner_uuid,owner_name,material,item_name,item_lore,created_item_at,last_seen_at,last_action,detection_count,last_location,snapshot_version,payload,sha256,captured_at,state,created_at,updated_at,detail FROM tag_publications",
            "INSERT INTO tag_publications(publication_id,source_key,source_digest,code,item_uuid,owner_uuid,owner_name,material,item_name,item_lore,created_item_at,last_seen_at,last_action,detection_count,last_location,snapshot_version,payload,sha256,captured_at,state,created_at,updated_at,detail,server_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (source, insert) -> {
                for (int i = 1; i <= 23; i++) insert.setObject(i, source.getObject(i));
                insert.setString(24, serverId);
            });
    }

    private void copyPluginStats(Connection sourceConnection, Connection target) throws SQLException {
        try (var select = sourceConnection.createStatement();
             var rows = select.executeQuery(
                 "SELECT duplicates_detected,last_updated FROM plugin_stats WHERE id = 1");
             var update = target.prepareStatement("""
                 UPDATE plugin_stats
                 SET schema_version = ?, duplicates_detected = ?, last_updated = ?
                 WHERE id = 1
                 """)) {
            if (!rows.next()) throw new SQLException("SQLite schema metadata row id=1 is missing");
            update.setInt(1, MySqlSchemaManager.CURRENT_SCHEMA_VERSION);
            update.setObject(2, rows.getObject(1));
            update.setObject(3, rows.getObject(2));
            if (update.executeUpdate() != 1) {
                throw new SQLException("MySQL schema metadata row id=1 is missing");
            }
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(java.sql.ResultSet source, java.sql.PreparedStatement target) throws SQLException;
    }

    private void transfer(Connection sourceConnection, Connection target, String selectSql,
                          String insertSql, Binder binder) throws SQLException {
        try (var select = sourceConnection.createStatement();
             var rows = select.executeQuery(selectSql);
             var insert = target.prepareStatement(insertSql)) {
            while (rows.next()) {
                binder.bind(rows, insert);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
