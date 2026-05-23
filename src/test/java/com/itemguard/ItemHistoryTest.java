package com.itemguard;

import com.itemguard.data.ItemHistory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ItemHistoryTest {

    @Test
    void testHistoryCreation() {
        UUID itemUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        ItemHistory history = new ItemHistory(
            "TEST-0001",
            itemUuid,
            "PICKUP",
            "TestPlayer",
            playerUuid,
            "world (0, 64, 0)"
        );

        assertEquals("TEST-0001", history.getCode());
        assertEquals(itemUuid, history.getItemUuid());
        assertEquals("PICKUP", history.getAction());
        assertEquals("TestPlayer", history.getPlayerName());
        assertEquals(playerUuid, history.getPlayerUuid());
        assertEquals("world (0, 64, 0)", history.getLocation());
        assertNotNull(history.getTimestamp());
    }

    @Test
    void testParseLocation() {
        UUID itemUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        ItemHistory history = new ItemHistory(
            "TEST-0002",
            itemUuid,
            "DROP",
            "Player2",
            playerUuid,
            "survival (100, 64, -50)"
        );

        assertEquals("survival", history.getWorld());
        assertEquals(100, history.getX());
        assertEquals(64, history.getY());
        assertEquals(-50, history.getZ());
    }

    @Test
    void testRelativeTime() {
        UUID itemUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        ItemHistory history = new ItemHistory(
            "TEST-0003",
            itemUuid,
            "SPAWN",
            "Player3",
            playerUuid,
            "world (0, 64, 0)"
        );

        String relative = history.getRelativeTime();
        assertNotNull(relative);
        assertTrue(relative.endsWith("s ago") || relative.endsWith("m ago"));
    }

    @Test
    void testFormattedTimestamp() {
        UUID itemUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        ItemHistory history = new ItemHistory(
            "TEST-0004",
            itemUuid,
            "CRAFT",
            "Player4",
            playerUuid,
            "world (0, 64, 0)"
        );

        String formatted = history.getFormattedTimestamp();
        assertNotNull(formatted);
        assertTrue(formatted.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void testNullLocationParsing() {
        UUID itemUuid = UUID.randomUUID();
        ItemHistory history = new ItemHistory(
            "TEST-0005",
            itemUuid,
            "USE",
            "Player5",
            UUID.randomUUID(),
            null
        );

        assertEquals("unknown", history.getWorld());
        assertEquals(0, history.getX());
        assertEquals(0, history.getY());
        assertEquals(0, history.getZ());
    }

    @Test
    void testEmptyLocationParsing() {
        UUID itemUuid = UUID.randomUUID();
        ItemHistory history = new ItemHistory(
            "TEST-0006",
            itemUuid,
            "DEATH",
            "Player6",
            UUID.randomUUID(),
            ""
        );

        assertEquals("unknown", history.getWorld());
    }
}
