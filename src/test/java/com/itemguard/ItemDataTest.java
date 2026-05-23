package com.itemguard;

import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ItemDataTest {

    @Test
    void testItemDataCreation() {
        String code = "TEST-1234";
        UUID itemUuid = UUID.randomUUID();
        ItemData data = new ItemData(code, itemUuid);

        assertEquals(code, data.getCode());
        assertEquals(itemUuid, data.getItemUuid());
        assertEquals(1, data.getCurrentCount());
        assertNotNull(data.getCreatedAt());
        assertNotNull(data.getLastSeenAt());
    }

    @Test
    void testItemDataCountIncrement() {
        ItemData data = new ItemData("TEST-0001", UUID.randomUUID());
        int initial = data.getCurrentCount();
        data.incrementCount();
        assertEquals(initial + 1, data.getCurrentCount());
    }

    @Test
    void testItemDataDisplayName() {
        ItemData data = new ItemData("TEST-0002", UUID.randomUUID());
        data.setItemName("My Custom Sword");

        assertEquals("My Custom Sword", data.getDisplayName());

        ItemData data2 = new ItemData("TEST-0003", UUID.randomUUID());
        data2.setMaterial(org.bukkit.Material.DIAMOND_SWORD);
        assertEquals("Diamond Sword", data2.getDisplayName());
    }

    @Test
    void testItemDataDefaultDisplayName() {
        ItemData data = new ItemData("TEST-0004", UUID.randomUUID());
        assertEquals("Unknown Item", data.getDisplayName());
    }
}
