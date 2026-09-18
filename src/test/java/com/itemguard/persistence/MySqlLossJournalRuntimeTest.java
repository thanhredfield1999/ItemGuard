package com.itemguard.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import com.itemguard.listeners.ItemLossPolicy;
import com.itemguard.listeners.LossRecord;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("mysql")
class MySqlLossJournalRuntimeTest {
    private static final String CODE = "LOSS01";
    private static final UUID ITEM_UUID = UUID.fromString(
        "00000000-0000-0000-0000-000000000301"
    );
    private static final UUID PLAYER_UUID = UUID.fromString(
        "00000000-0000-0000-0000-000000000302"
    );

    @Test
    void lossJournalWritesMandatoryServerIdentityAndKeepsTransactionAtomic() throws Exception {
        MySqlTestSupport.freshSchema().close();
        try (MySqlConnectionOwner owner = owner()) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner, "server-loss");
            ItemData item = new ItemData(CODE, ITEM_UUID);
            item.setOwnerUuid(PLAYER_UUID);
            item.setOwnerName("Thanh");
            item.setLastAction("PICKUP");
            item.setLastLocation("world (1, 64, 1)");
            repository.saveItem(item);
            owner.flush();

            ItemHistory pickup = new ItemHistory(
                CODE, ITEM_UUID, "PICKUP", "Thanh", PLAYER_UUID, "world (1, 64, 1)"
            );
            pickup.setTimestamp(10L);
            repository.logHistory(pickup);
            owner.flush();

            SqliteLossJournal journal = new SqliteLossJournal(
                owner,
                () -> 20L,
                "server-loss"
            );
            LossRecord loss = new LossRecord(
                CODE,
                ITEM_UUID,
                ItemLossPolicy.forInventoryRemoval(),
                PLAYER_UUID,
                1L,
                UUID.fromString("00000000-0000-0000-0000-000000000303"),
                () -> true,
                "world (1, 64, 1)"
            );

            assertTrue(journal.recordLoss(loss).get(5, TimeUnit.SECONDS));
            assertEquals("CLEARED", repository.getItem(CODE).orElseThrow().getLastAction());
            assertEquals(2, repository.getHistoryCount(CODE));

            try (var connection = MySqlTestSupport.connect();
                 var statement = connection.prepareStatement("""
                     SELECT server_id, action FROM item_history
                     WHERE code = ? ORDER BY id DESC LIMIT 1
                     """)) {
                statement.setString(1, CODE);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("server-loss", rows.getString("server_id"));
                    assertEquals("CLEARED", rows.getString("action"));
                }
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
}
