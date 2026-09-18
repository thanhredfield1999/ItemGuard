package com.itemguard.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import com.itemguard.dupe.HolderType;
import com.itemguard.dupe.ItemObservation;
import com.itemguard.dupe.ObservationKey;
import com.itemguard.reclaim.ReclaimClaim;
import com.itemguard.reclaim.ReclaimClaimState;
import com.itemguard.search.ItemSearchMode;
import com.itemguard.search.ItemSearchRequest;
import com.itemguard.search.ItemSearchState;
import com.itemguard.snapshot.ItemSnapshot;
import com.itemguard.tracking.TagPublication;
import com.itemguard.tracking.TagPublicationState;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("mysql")
class MySqlRepositoryRuntimeTest {
    private static final String CODE = "RTM001";
    private static final UUID ITEM_UUID = UUID.fromString(
        "00000000-0000-0000-0000-000000000201"
    );
    private static final UUID PLAYER_UUID = UUID.fromString(
        "00000000-0000-0000-0000-000000000202"
    );

    @Test
    void repositoryWritePathsUseMysqlDialectAndPreserveServerIdentity() throws Exception {
        MySqlTestSupport.freshSchema().close();
        try (MySqlConnectionOwner owner = owner()) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner, "server-a");
            ItemData item = item(CODE, ITEM_UUID);
            ItemSnapshot snapshot = snapshot();

            repository.saveItemWithSnapshot(item, snapshot, 20L);
            assertEquals(ITEM_UUID, repository.getItem(CODE).orElseThrow().getItemUuid());
            assertTrue(Arrays.equals(snapshot.payload(), repository.getSnapshot(CODE).orElseThrow().payload()));

            ItemHistory history = new ItemHistory(
                CODE, ITEM_UUID, "PICKUP", "Thanh", PLAYER_UUID, "world (1, 2, 3)"
            );
            history.setTimestamp(30L);
            repository.logHistory(history);
            owner.flush();
            assertEquals("PICKUP", repository.getHistory(CODE, 10).get(0).getAction());

            ItemObservation priorOne = new ItemObservation(
                ITEM_UUID,
                CODE,
                39L,
                new ObservationKey(HolderType.PLAYER, PLAYER_UUID.toString(), 1),
                39L
            );
            ItemObservation priorTwo = new ItemObservation(
                ITEM_UUID,
                CODE,
                39L,
                new ObservationKey(HolderType.PLAYER, PLAYER_UUID.toString(), 2),
                39L
            );
            repository.recordObservation(priorOne);
            repository.recordObservation(priorTwo);
            owner.flush();
            repository.completeObservationEpoch(39L);
            owner.flush();

            ItemObservation observation = new ItemObservation(
                ITEM_UUID,
                CODE,
                40L,
                new ObservationKey(HolderType.PLAYER, PLAYER_UUID.toString(), 0),
                40L
            );
            repository.recordObservation(observation);
            repository.recordObservation(observation);
            owner.flush();
            assertEquals(1, repository.countObservations(ITEM_UUID, 40L));
            assertEquals(40L, repository.getMaximumPersistedObservationEpoch());

            assertEquals(1, repository.getHistorySummariesByPlayerAsync(PLAYER_UUID, 10, 0)
                .get(5, TimeUnit.SECONDS).size());

            ItemObservation currentSecond = new ItemObservation(
                ITEM_UUID,
                CODE,
                40L,
                new ObservationKey(HolderType.PLAYER, PLAYER_UUID.toString(), 3),
                40L
            );
            repository.recordObservation(currentSecond);
            owner.flush();
            assertEquals(2, repository.countObservations(ITEM_UUID, 40L));
            assertEquals(1, repository.completeObservationEpochAndAudit(
                40L, true, com.itemguard.dupe.DuplicateAction.NOTIFY, 0L, 100L
            ).get(5, TimeUnit.SECONDS).size());

            ItemSearchRequest request = new ItemSearchRequest(
                CODE, ItemSearchMode.FIND, ItemSearchState.ACTIVE, PLAYER_UUID, "Thanh",
                50L, 5_000L, 50L
            );
            assertTrue(repository.startSearchRequest(request));
            assertFalse(repository.startSearchRequest(request));

            ReclaimClaim claim = new ReclaimClaim(
                UUID.fromString("00000000-0000-0000-0000-000000000203"),
                "runtime-idempotency",
                PLAYER_UUID,
                CODE,
                ReclaimClaimState.PENDING,
                60L,
                60L,
                "runtime"
            );
            assertTrue(repository.beginReclaimClaim(claim));
            assertFalse(repository.beginReclaimClaim(claim));

            TagPublication publication = new TagPublication(
                UUID.fromString("00000000-0000-0000-0000-000000000204"),
                "PLAYER_SLOT:runtime:0",
                new byte[32],
                item("PUB001", UUID.fromString("00000000-0000-0000-0000-000000000205")),
                snapshot(),
                70L,
                TagPublicationState.PREPARED,
                70L,
                70L,
                "runtime"
            );
            assertEquals(publication, repository.reserve(publication).get(5, TimeUnit.SECONDS));
            assertNotNull(repository.publish(publication.publicationId(), 80L)
                .get(5, TimeUnit.SECONDS));
            assertTrue(repository.getItem("PUB001").isPresent());

            assertEquals("MYSQL", repository.getStats().getDatabaseType());
        }

        try (var connection = MySqlTestSupport.connect();
             var statement = connection.prepareStatement(
                 "SELECT server_id FROM item_history WHERE code = ?")) {
            statement.setString(1, CODE);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("server-a", rows.getString(1));
            }
        }
    }

    private static MySqlConnectionOwner owner() {
        return new MySqlConnectionOwner(
            MySqlConnectionOwner.hikariDataSource(
                "jdbc:mysql://127.0.0.1:33316/itemguard_premium"
                    + "?allowPublicKeyRetrieval=true&sslMode=PREFERRED&connectionTimeZone=UTC",
                "itemguard",
                "itemguard_test_password",
                3,
                1,
                5_000
            ),
            3,
            ignored -> { }
        );
    }

    private static ItemData item(String code, UUID itemUuid) {
        ItemData item = new ItemData(code, itemUuid);
        item.setOwnerUuid(PLAYER_UUID);
        item.setOwnerName("Thanh");
        item.setLastAction("PICKUP");
        item.setLastSeenAt(20L);
        item.setCreatedAt(10L);
        return item;
    }

    private static ItemSnapshot snapshot() {
        return new ItemSnapshot(1, new byte[] {1, 2, 3}, new byte[32]);
    }
}
