package com.itemguard.persistence;

import com.itemguard.data.ItemData;
import com.itemguard.data.PluginStats;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncItemReadRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void asyncReadApisUsePersistedItemData() {
        UUID ownerUuid = UUID.randomUUID();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("async-read.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            ItemData item = new ItemData("AB12CD", UUID.randomUUID());
            item.setOwnerUuid(ownerUuid);
            item.setOwnerName("Thanh");
            item.setMaterial(Material.DIAMOND_SWORD);
            item.setItemName("Guarded Sword");
            item.setCreatedAt(1_000L);
            item.setLastSeenAt(2_000L);
            item.setLastAction("PICKUP");
            repository.saveItem(item);
            owner.flush();

            assertEquals("AB12CD", repository.getItemAsync("AB12CD").join().orElseThrow().getCode());
            assertEquals(1, repository.getItemsByPlayerAsync(ownerUuid).join().size());
            assertEquals(1, repository.searchItemsAsync("Guarded").join().size());
            PluginStats stats = repository.getStatsAsync().join();
            assertEquals(1, stats.getTotalItems());
            assertTrue(stats.getTotalHistory() >= 0);
        }
    }
}
