package com.itemguard.persistence;

import com.itemguard.dupe.DuplicateDetector;
import com.itemguard.dupe.DuplicateStatus;
import com.itemguard.dupe.HolderType;
import com.itemguard.dupe.ItemObservation;
import com.itemguard.dupe.ObservationKey;
import com.itemguard.multiserver.CrossServerAssessment;
import com.itemguard.multiserver.CrossServerFindingPolicy;
import com.itemguard.multiserver.CrossServerSighting;
import com.itemguard.multiserver.CrossServerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3's schema and rule together, against a real MySQL server.
 *
 * <p>The three assertions that matter are a triangle: the cross-server rule fires on two servers
 * where the epoch rule cannot see anything, the epoch rule still fires on one server where the
 * cross-server rule does not, and a row without a server name is refused. The first two are what
 * "the two rule sets stay separate" means in practice — the new rule must not have been bought by
 * weakening the old one.
 */
@Tag("mysql")
class MySqlCrossServerFindingTest {

    private static final String CODE = MySqlTestSupport.IDENTITY_CODE;
    private static final UUID IDENTITY = UUID.fromString(MySqlTestSupport.IDENTITY_UUID);
    private static final long NOW = 1_700_000_000_000L;
    private static final long EPOCH_A = 41L;
    private static final long EPOCH_B = 42L;

    private static void insertObservation(Connection connection, long epoch, String holderId,
                                          int slot, long observedAt, String serverId)
        throws SQLException {
        MySqlTestSupport.execute(connection, """
            INSERT INTO item_observations
            (item_uuid, code, scan_epoch, epoch_complete, holder_type, holder_id, slot,
             observed_at, server_id)
            VALUES ('%s', '%s', %d, 1, 'PLAYER', '%s', %d, %d, %s)
            """.formatted(IDENTITY, CODE, epoch, holderId, slot, observedAt,
                serverId == null ? "NULL" : "'" + serverId + "'"));
        connection.commit();
    }

    private static List<ItemObservation> observations(Connection connection) throws SQLException {
        List<ItemObservation> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 SELECT item_uuid, code, scan_epoch, holder_type, holder_id, slot, observed_at
                 FROM item_observations ORDER BY observation_id
                 """)) {
            while (result.next()) {
                rows.add(new ItemObservation(
                    UUID.fromString(result.getString("item_uuid")),
                    result.getString("code"),
                    result.getLong("scan_epoch"),
                    new ObservationKey(
                        HolderType.valueOf(result.getString("holder_type")),
                        result.getString("holder_id"),
                        result.getInt("slot")),
                    result.getLong("observed_at")));
            }
        }
        return rows;
    }

    private static List<CrossServerSighting> sightings(Connection connection) throws SQLException {
        List<CrossServerSighting> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 SELECT item_uuid, server_id, observed_at FROM item_observations
                 ORDER BY observation_id
                 """)) {
            while (result.next()) {
                rows.add(new CrossServerSighting(
                    UUID.fromString(result.getString("item_uuid")),
                    result.getString("server_id"),
                    result.getLong("observed_at"),
                    false));
            }
        }
        return rows;
    }

    @Test
    @DisplayName("two servers, two epochs: the cross-server rule fires where the epoch rule cannot")
    void crossServerRuleFiresWhereTheEpochRuleIsSilent() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            insertObservation(connection, EPOCH_A, "LiteMember", 10, NOW - 60_000, "survival-1");
            insertObservation(connection, EPOCH_B, "LiteMember", 10, NOW - 30_000, "survival-2");

            CrossServerAssessment assessment = new CrossServerFindingPolicy()
                .assess(IDENTITY, NOW, 30 * 60_000L, sightings(connection));
            assertEquals(CrossServerStatus.SEEN_ON_MULTIPLE_SERVERS, assessment.status());
            assertEquals(List.of("survival-1", "survival-2"), assessment.servers());

            assertEquals(DuplicateStatus.CLEAN,
                new DuplicateDetector().assess(IDENTITY, EPOCH_A, true, observations(connection))
                    .status(),
                "the epoch rule sees one observation per epoch, so it says nothing here - which is "
                    + "exactly why the cross-server case needed its own rule");
        }
    }

    @Test
    @DisplayName("one server, one epoch: the epoch rule still fires and the new rule does not")
    void theEpochRuleIsUnweakened() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            insertObservation(connection, EPOCH_A, "LiteMember", 10, NOW - 30_000, "survival-1");
            insertObservation(connection, EPOCH_A, "LiteStaff", 10, NOW - 30_000, "survival-1");

            assertEquals(DuplicateStatus.CONFIRMED,
                new DuplicateDetector().assess(IDENTITY, EPOCH_A, true, observations(connection))
                    .status(),
                "the shipped duplicate rule must still confirm a same-epoch, same-server pair");
            assertEquals(CrossServerStatus.NONE,
                new CrossServerFindingPolicy()
                    .assess(IDENTITY, NOW, 30 * 60_000L, sightings(connection)).status(),
                "one server is not a cross-server finding");
        }
    }

    @Test
    @DisplayName("an observation without a server name is refused by the schema")
    void aSightingWithoutAServerIsRefused() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            assertThrows(SQLException.class,
                () -> insertObservation(connection, EPOCH_A, "LiteMember", 10, NOW, null),
                "a row that cannot say which server saw the item is not usable evidence");
            assertEquals(0, MySqlTestSupport.count(connection, "item_observations"));
        }
    }

    @Test
    @DisplayName("the window query has its index: this is a scale question, not a preference")
    void theWindowQueryIsIndexed() throws Exception {
        try (Connection connection = MySqlTestSupport.freshSchema()) {
            List<String> columns = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT COLUMN_NAME FROM information_schema.statistics
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item_observations'
                      AND INDEX_NAME = 'idx_observation_identity_server'
                    ORDER BY SEQ_IN_INDEX
                    """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) columns.add(rows.getString(1));
                }
            }
            assertEquals(List.of("item_uuid", "server_id", "observed_at"), columns,
                "without this index the cross-server question reads every observation ever taken");
            assertTrue(columns.size() == 3);
        }
    }
}
