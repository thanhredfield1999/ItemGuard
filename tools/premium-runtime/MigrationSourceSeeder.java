package premiummigration;

import com.itemguard.persistence.SqliteSchemaManager;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Fixture-only creator for a real schema-version-8 ItemGuard SQLite source. */
public final class MigrationSourceSeeder {
    private static final String CODE = "MIGP01";
    private static final String ITEM_UUID = "00000000-0000-0000-0000-000000000301";

    private MigrationSourceSeeder() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: MigrationSourceSeeder <sqlite-path>");
        }
        Path source = Path.of(args[0]).toAbsolutePath();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            connection.setAutoCommit(false);
            new SqliteSchemaManager().initialize(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    INSERT INTO tracked_items
                    (code,item_uuid,material,item_name,created_at,last_seen_at,last_action,detection_count)
                    VALUES ('MIGP01','00000000-0000-0000-0000-000000000301',
                            'DIAMOND_SWORD','Paper migration sword',100,200,'PICKUP',2)
                    """);
                statement.executeUpdate("""
                    INSERT INTO item_history
                    (code,item_uuid,action,player_name,location,world,x,y,z,timestamp,additional_data)
                    VALUES ('MIGP01','00000000-0000-0000-0000-000000000301',
                            'PICKUP','MigrationPlayer','world (10, 64, 20)','world',10,64,20,210,'paper-source')
                    """);
                statement.executeUpdate("""
                    INSERT INTO item_observations
                    (item_uuid,code,scan_epoch,epoch_complete,holder_type,holder_id,slot,observed_at)
                    VALUES ('00000000-0000-0000-0000-000000000301','MIGP01',17,1,
                            'PLAYER','migration-player',0,220)
                    """);
                statement.executeUpdate("""
                    INSERT INTO duplicate_findings
                    (item_uuid,code,scan_epoch,status,distinct_locations,action,created_at,detail)
                    VALUES ('00000000-0000-0000-0000-000000000301','MIGP01',17,
                            'CLEAN',1,'NOTIFY',230,'paper-source')
                    """);
                statement.executeUpdate("""
                    INSERT INTO item_search_requests
                    (code,mode,state,actor_name,created_at,expires_at,updated_at)
                    VALUES ('MIGP01','FIND','ACTIVE','MigrationPlayer',240,500,250)
                    """);
                statement.executeUpdate("""
                    INSERT INTO item_snapshots
                    (code,snapshot_version,payload,sha256,captured_at)
                    VALUES ('MIGP01',1,X'010203',X'040506',260)
                    """);
                statement.executeUpdate("""
                    INSERT INTO reclaim_claims
                    (claim_id,idempotency_key,player_uuid,code,state,requested_at,updated_at,detail)
                    VALUES ('00000000-0000-0000-0000-000000000302','paper-migration-claim',
                            '00000000-0000-0000-0000-000000000303','MIGP01','COMMITTED',270,271,'paper-source')
                    """);
                statement.executeUpdate("""
                    INSERT INTO tag_publications
                    (publication_id,source_key,source_digest,code,item_uuid,owner_name,material,item_name,
                     created_item_at,last_seen_at,last_action,detection_count,last_location,snapshot_version,
                     payload,sha256,captured_at,state,created_at,updated_at,detail)
                    VALUES ('00000000-0000-0000-0000-000000000304','paper-migration-source',X'01','MIGP01',
                            '00000000-0000-0000-0000-000000000301','MigrationPlayer','DIAMOND_SWORD',
                            'Paper migration sword',100,200,'PICKUP',2,'world (10, 64, 20)',1,
                            X'010203',X'040506',280,'PUBLISHED',281,282,'paper-source')
                    """);
                statement.executeUpdate("""
                    UPDATE plugin_stats
                    SET duplicates_detected=7,last_updated=290
                    WHERE id=1
                    """);
                connection.commit();
            }
        }
        System.out.println("SOURCE_SEEDED schema=8 code=" + CODE + " rows_per_data_table=1");
    }
}
