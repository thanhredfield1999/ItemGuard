package com.itemguard.persistence;

import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemHistorySummaryRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void retainedSummaryUsesTrackedPreviewAndCountsEveryRecordedActionForOneIdentity() {
        UUID owner = UUID.randomUUID();
        ItemData sword = item("SWORD01", owner, Material.DIAMOND_SWORD, "Audit Sword");
        ItemData bow = item("BOW002", owner, Material.BOW, "Audit Bow");

        try (SqliteConnectionOwner connection = new SqliteConnectionOwner(tempDir.resolve("summary.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(connection);
            repository.saveItem(sword);
            repository.saveItem(bow);
            repository.logHistory(history(sword, owner, "PICKUP", 1_000L));
            repository.logHistory(history(sword, owner, "DROP", 2_000L));
            repository.logHistory(history(sword, owner, "PICKUP", 3_000L));
            repository.logHistory(history(bow, owner, "PICKUP", 4_000L));
            connection.flush();

            var summaries = repository.getHistorySummariesByPlayerAsync(owner, 45, 0).join();

            assertEquals(2, summaries.size());
            var newest = summaries.getFirst();
            assertEquals("BOW002", newest.item().getCode());
            assertEquals(Material.BOW, newest.item().getMaterial());
            assertEquals("Audit Bow", newest.item().getItemName());
            var swordSummary = summaries.stream()
                .filter(summary -> summary.item().getCode().equals("SWORD01"))
                .findFirst().orElseThrow();
            assertEquals(3, swordSummary.totalEvents());
            assertEquals(Map.of("PICKUP", 2, "DROP", 1), swordSummary.actionCounts());
        }
    }

    private static ItemData item(String code, UUID owner, Material material, String name) {
        ItemData item = new ItemData(code, UUID.randomUUID());
        item.setOwnerUuid(owner);
        item.setOwnerName("Tester");
        item.setMaterial(material);
        item.setItemName(name);
        item.setCreatedAt(1_000L);
        item.setLastSeenAt(1_000L);
        item.setLastAction("SPAWN");
        return item;
    }

    private static ItemHistory history(ItemData item, UUID owner, String action, long timestamp) {
        ItemHistory history = new ItemHistory(item.getCode(), item.getItemUuid(), action, "Tester", owner,
            "world (1, 64, 1)");
        history.setTimestamp(timestamp);
        return history;
    }
}
