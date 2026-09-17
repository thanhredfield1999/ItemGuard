package com.itemguard.persistence;

import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemHistoryQueryRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void playerHistoryIsGloballyBoundedAndNewestFirst() {
        UUID ownerUuid = UUID.randomUUID();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("player-history.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            ItemData first = item("AA11AA", ownerUuid);
            ItemData second = item("BB22BB", ownerUuid);
            repository.saveItem(first);
            repository.saveItem(second);
            repository.logHistory(history(first, ownerUuid, 1_000L));
            repository.logHistory(history(second, ownerUuid, 3_000L));
            repository.logHistory(history(first, ownerUuid, 2_000L));
            owner.flush();

            List<ItemHistory> result = repository.getHistoryByPlayerAsync(ownerUuid, 2).join();

            assertEquals(List.of(3_000L, 2_000L),
                result.stream().map(ItemHistory::getTimestamp).toList());
        }
    }

    private ItemData item(String code, UUID ownerUuid) {
        ItemData item = new ItemData(code, UUID.randomUUID());
        item.setOwnerUuid(ownerUuid);
        item.setOwnerName("Thanh");
        item.setMaterial(Material.DIAMOND_SWORD);
        item.setItemName("Guarded Sword");
        item.setCreatedAt(1_000L);
        item.setLastSeenAt(1_000L);
        item.setLastAction("SPAWN");
        return item;
    }

    private ItemHistory history(ItemData item, UUID ownerUuid, long timestamp) {
        ItemHistory history = new ItemHistory(
            item.getCode(), item.getItemUuid(), "PICKUP", "Thanh", ownerUuid,
            "world (1, 64, 1)"
        );
        history.setTimestamp(timestamp);
        return history;
    }
}
