package com.itemguard.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("mysql")
class MySqlMigrationServiceTest {
    private static final String CODE = "MIG001";
    private static final String UUID = "00000000-0000-0000-0000-000000000099";

    @TempDir
    Path tempDir;

    @Test
    void dryRunThenMigrateCopiesEveryTableAndLeavesSourceUntouched() throws Exception {
        Path source = createSourceDatabase();
        long sourceBytes = Files.size(source);
        var sourceModified = Files.getLastModifiedTime(source);

        try (MySqlConnectionOwner owner = owner()) {
            var service = new MySqlMigrationService(owner, source, "survival-1");
            MigrationReport dryRun = service.dryRun();

            assertTrue(dryRun.dryRun());
            assertFalse(dryRun.migrated());
            assertEquals(1L, dryRun.sourceRows().get("tracked_items"));
            assertEquals(1L, dryRun.sourceRows().get("item_history"));
            assertEquals(1L, dryRun.sourceRows().get("item_observations"));
            assertEquals(1L, dryRun.sourceRows().get("duplicate_findings"));
            assertEquals(1L, dryRun.sourceRows().get("item_search_requests"));
            assertEquals(1L, dryRun.sourceRows().get("item_snapshots"));
            assertEquals(1L, dryRun.sourceRows().get("reclaim_claims"));
            assertEquals(1L, dryRun.sourceRows().get("tag_publications"));
            assertTrue(dryRun.targetEmpty());

            MigrationReport migrated = service.migrate();

            assertFalse(migrated.dryRun());
            assertTrue(migrated.migrated());
            assertTrue(migrated.verified());
            assertEquals(dryRun.sourceRows(), migrated.sourceRows());
            assertEquals(dryRun.sourceRows(), migrated.targetRows());
            assertEquals(sourceBytes, Files.size(source));
            assertEquals(sourceModified, Files.getLastModifiedTime(source));

            Map<String, Object> target = owner.call(connection -> readTarget(connection));
            assertEquals(CODE, target.get("code"));
            assertEquals(UUID, target.get("item_uuid"));
            assertEquals("survival-1", target.get("history_server_id"));
            assertEquals("survival-1", target.get("observation_server_id"));
            assertEquals("survival-1", target.get("publication_server_id"));
            assertEquals(MySqlSchemaManager.CURRENT_SCHEMA_VERSION, target.get("schema_version"));
            assertEquals(1L, target.get("tracked_items"));
            assertEquals(1L, target.get("item_history"));
            assertEquals(1L, target.get("item_observations"));
            assertEquals(1L, target.get("duplicate_findings"));
            assertEquals(1L, target.get("item_search_requests"));
            assertEquals(1L, target.get("item_snapshots"));
            assertEquals(1L, target.get("reclaim_claims"));
            assertEquals(1L, target.get("tag_publications"));
        }
    }

    @Test
    void nonEmptyTargetIsRefusedBeforeAnyMigrationWrite() throws Exception {
        Path source = createSourceDatabase();
        try (MySqlConnectionOwner owner = owner()) {
            owner.call(connection -> {
                try (var statement = connection.prepareStatement(
                    "INSERT INTO tracked_items(code,item_uuid,created_at,last_seen_at) VALUES(?,?,1,1)")) {
                    statement.setString(1, "EXIST1");
                    statement.setString(2, "00000000-0000-0000-0000-000000000098");
                    statement.executeUpdate();
                }
                return null;
            });
            var service = new MySqlMigrationService(owner, source, "survival-1");
            IllegalStateException failure = assertThrows(IllegalStateException.class, service::migrate);
            assertTrue(failure.getMessage().contains("not empty"), failure::getMessage);
            assertEquals(1L, (long) owner.call(c -> count(c, "tracked_items")));
            assertEquals(0L, (long) owner.call(c -> count(c, "item_history")));
        }
    }

    private Path createSourceDatabase() throws Exception {
        Path source = tempDir.resolve("itemguard-source.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            connection.setAutoCommit(false);
            new SqliteSchemaManager().initialize(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    INSERT INTO tracked_items
                    (code,item_uuid,material,item_name,created_at,last_seen_at)
                    VALUES ('MIG001','00000000-0000-0000-0000-000000000099','DIAMOND_SWORD','Migrated sword',10,20)
                    """);
                statement.executeUpdate("""
                    INSERT INTO item_history
                    (code,item_uuid,action,player_name,player_uuid,location,world,x,y,z,timestamp,additional_data)
                    VALUES ('MIG001','00000000-0000-0000-0000-000000000099','PICKUP','Thanh',NULL,'world (1, 2, 3)','world',1,2,3,30,'source')
                    """);
                statement.executeUpdate("""
                    INSERT INTO item_observations
                    (item_uuid,code,scan_epoch,epoch_complete,holder_type,holder_id,slot,observed_at)
                    VALUES ('00000000-0000-0000-0000-000000000099','MIG001',7,1,'PLAYER','player-1',0,40)
                    """);
                statement.executeUpdate("""
                    INSERT INTO duplicate_findings
                    (item_uuid,code,scan_epoch,status,distinct_locations,action,created_at,detail)
                    VALUES ('00000000-0000-0000-0000-000000000099','MIG001',7,'CLEAN',1,'NOTIFY',41,'source')
                    """);
                statement.executeUpdate("""
                    INSERT INTO item_search_requests
                    (code,mode,state,actor_name,created_at,expires_at,updated_at)
                    VALUES ('MIG001','FIND','ACTIVE','Thanh',10,100,20)
                    """);
                statement.executeUpdate("""
                    INSERT INTO item_snapshots
                    (code,snapshot_version,payload,sha256,captured_at)
                    VALUES ('MIG001',1,X'010203',X'040506',50)
                    """);
                statement.executeUpdate("""
                    INSERT INTO reclaim_claims
                    (claim_id,idempotency_key,player_uuid,code,state,requested_at,updated_at,detail)
                    VALUES ('00000000-0000-0000-0000-000000000097','idem-1','00000000-0000-0000-0000-000000000096','MIG001','COMMITTED',60,61,'source')
                    """);
                statement.executeUpdate("""
                    INSERT INTO tag_publications
                    (publication_id,source_key,source_digest,code,item_uuid,owner_uuid,owner_name,material,item_name,item_lore,
                     created_item_at,last_seen_at,last_action,detection_count,last_location,snapshot_version,payload,sha256,
                     captured_at,state,created_at,updated_at,detail)
                    VALUES ('00000000-0000-0000-0000-000000000095','source-1',X'01','MIG001',
                     '00000000-0000-0000-0000-000000000099',NULL,'Thanh','DIAMOND_SWORD','Migrated sword',NULL,
                     10,20,'PICKUP',0,'world (1, 2, 3)',1,X'010203',X'040506',50,'PUBLISHED',70,71,'source')
                    """);
                connection.commit();
            }
        }
        return source;
    }

    private MySqlConnectionOwner owner() {
        MySqlTestSupport.dropTargetTablesForMigration();
        return new MySqlConnectionOwner(MySqlConnectionOwner.hikariDataSource(
            "jdbc:mysql://127.0.0.1:33316/itemguard_premium?allowPublicKeyRetrieval=true&sslMode=PREFERRED&connectionTimeZone=UTC",
            "itemguard", "itemguard_test_password", 3, 1, 5000), 3, ignored -> { });
    }

    private static Map<String, Object> readTarget(Connection connection) throws SQLException {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        try (var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT code,item_uuid FROM tracked_items")) {
                rows.next(); values.put("code", rows.getString(1)); values.put("item_uuid", rows.getString(2));
            }
            values.put("tracked_items", count(connection, "tracked_items"));
            values.put("item_history", count(connection, "item_history"));
            values.put("item_observations", count(connection, "item_observations"));
            values.put("duplicate_findings", count(connection, "duplicate_findings"));
            values.put("item_search_requests", count(connection, "item_search_requests"));
            values.put("item_snapshots", count(connection, "item_snapshots"));
            values.put("reclaim_claims", count(connection, "reclaim_claims"));
            values.put("tag_publications", count(connection, "tag_publications"));
            try (var rows = statement.executeQuery("SELECT server_id FROM item_history")) {
                rows.next(); values.put("history_server_id", rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT server_id FROM item_observations")) {
                rows.next(); values.put("observation_server_id", rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT server_id FROM tag_publications")) {
                rows.next(); values.put("publication_server_id", rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT schema_version FROM plugin_stats WHERE id=1")) {
                rows.next(); values.put("schema_version", rows.getInt(1));
            }
        }
        return values;
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next(); return rows.getLong(1);
        }
    }
}
