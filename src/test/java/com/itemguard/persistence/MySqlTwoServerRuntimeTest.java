package com.itemguard.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itemguard.dupe.DuplicateDetector;
import com.itemguard.dupe.DuplicateStatus;
import com.itemguard.dupe.HolderType;
import com.itemguard.dupe.ItemObservation;
import com.itemguard.dupe.ObservationKey;
import com.itemguard.multiserver.CrossServerAssessment;
import com.itemguard.multiserver.CrossServerFindingPolicy;
import com.itemguard.multiserver.CrossServerSighting;
import com.itemguard.multiserver.CrossServerStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M6's controlled shared-database fixture: two independent ItemGuard owners, one MySQL server.
 *
 * <p>This deliberately does not claim Paper lifecycle or client movement. It proves the database
 * boundary that a two-server Paper journey depends on: separate pools write named sightings to one
 * durable schema, the cross-server rule reports both names, and the old same-server epoch rule
 * still confirms two holders in one epoch.
 */
@Tag("mysql")
class MySqlTwoServerRuntimeTest {
    private static final String CODE = "M6ABC1";
    private static final UUID ITEM = UUID.fromString("00000000-0000-0000-0000-000000000061");
    private static final long NOW = 1_700_000_000_000L;

    @Test
    @DisplayName("two independent server owners share MySQL without crossing server identity")
    void twoServerOwnersProduceBothCrossServerAndSameServerEvidence() throws Exception {
        MySqlTestSupport.freshSchema().close();
        MySqlConnectionOwner.OwnedConnectionFactory sourceOne = factory();
        MySqlConnectionOwner.OwnedConnectionFactory sourceTwo = factory();
        try (MySqlConnectionOwner serverOne = new MySqlConnectionOwner(sourceOne, 2, ignored -> { });
             MySqlConnectionOwner serverTwo = new MySqlConnectionOwner(sourceTwo, 2, ignored -> { })) {
            serverOne.call(connection -> {
                insertTracked(connection);
                insertObservation(connection, 100, "server-one-player", 0, NOW - 60_000, "server-1");
                insertObservation(connection, 100, "server-one-staff", 1, NOW - 60_000, "server-1");
                return null;
            });
            serverTwo.call(connection -> {
                insertObservation(connection, 101, "server-two-player", 0, NOW - 30_000, "server-2");
                return null;
            });

            List<CrossServerSighting> sightings = serverOne.call(this::readSightings);
            CrossServerAssessment crossServer = new CrossServerFindingPolicy()
                .assess(ITEM, NOW, 30 * 60_000L, sightings);
            assertEquals(CrossServerStatus.SEEN_ON_MULTIPLE_SERVERS, crossServer.status());
            assertEquals(List.of("server-1", "server-2"), crossServer.servers());
            assertTrue(crossServer.statement().contains("server-1"));
            assertTrue(crossServer.statement().contains("server-2"));
            assertTrue(!crossServer.statement().toLowerCase().contains("duplicate"));

            List<ItemObservation> sameServerRows = serverTwo.call(this::readObservations);
            assertEquals(DuplicateStatus.CONFIRMED,
                new DuplicateDetector().assess(ITEM, 100, true, sameServerRows).status());

            assertEquals(3, (int) serverOne.call(connection -> count(connection, "item_observations")));
            assertEquals(1, (int) serverTwo.call(connection -> count(connection, "tracked_items")));
        }
    }

    private static MySqlConnectionOwner.OwnedConnectionFactory factory() {
        return MySqlConnectionOwner.hikariDataSource(
            "jdbc:mysql://127.0.0.1:33316/itemguard_premium"
                + "?allowPublicKeyRetrieval=true&sslMode=PREFERRED&connectionTimeZone=UTC",
            "itemguard", "itemguard_test_password", 2, 0, 5_000);
    }

    private static void insertTracked(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
            "INSERT INTO tracked_items(code,item_uuid,created_at,last_seen_at) VALUES(?,?,1,1)")) {
            statement.setString(1, CODE);
            statement.setString(2, ITEM.toString());
            statement.executeUpdate();
        }
    }

    private static void insertObservation(Connection connection, long epoch, String holder,
                                          int slot, long observedAt, String serverId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO item_observations
            (item_uuid,code,scan_epoch,epoch_complete,holder_type,holder_id,slot,observed_at,server_id)
            VALUES(?,?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, ITEM.toString());
            statement.setString(2, CODE);
            statement.setLong(3, epoch);
            statement.setInt(4, 1);
            statement.setString(5, "PLAYER");
            statement.setString(6, holder);
            statement.setInt(7, slot);
            statement.setLong(8, observedAt);
            statement.setString(9, serverId);
            statement.executeUpdate();
        }
    }

    private List<CrossServerSighting> readSightings(Connection connection) throws SQLException {
        List<CrossServerSighting> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "SELECT item_uuid,server_id,observed_at FROM item_observations ORDER BY observation_id")) {
            while (rows.next()) {
                result.add(new CrossServerSighting(UUID.fromString(rows.getString(1)),
                    rows.getString(2), rows.getLong(3), false));
            }
        }
        return result;
    }

    private List<ItemObservation> readObservations(Connection connection) throws SQLException {
        List<ItemObservation> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("""
                 SELECT item_uuid,code,scan_epoch,holder_type,holder_id,slot,observed_at
                 FROM item_observations ORDER BY observation_id
                 """)) {
            while (rows.next()) {
                result.add(new ItemObservation(UUID.fromString(rows.getString(1)), rows.getString(2),
                    rows.getLong(3), new ObservationKey(HolderType.valueOf(rows.getString(4)),
                    rows.getString(5), rows.getInt(6)), rows.getLong(7)));
            }
        }
        return result;
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
