package com.itemguard.commands;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryCommandWiringContractTest {

    @Test
    void playerHistoryUsesUuidAndGlobalLimitWithoutBlockingBukkitThread() throws Exception {
        String source = source();

        assertTrue(source.contains("openGUIForPlayer(player, target.getUniqueId(), target.getName(), limit)"));
        assertTrue(source.contains("openGUIForPlayer(player, player.getUniqueId(), player.getName(), limit)"));
        assertTrue(source.contains("getHistoryByPlayerAsync(targetPlayerUuid, limit)"));
        assertTrue(source.contains("UiMainThreadHandoff.dispatch(plugin"));
        assertFalse(source.contains("Bukkit.getScheduler().runTask(plugin"));
        assertFalse(source.contains("Bukkit.getPlayer(playerName)"));
        assertFalse(source.contains("getHistory(item.getCode(), 20)"));
    }

    @Test
    void codeHistoryUsesAsyncDatabaseRead() throws Exception {
        String source = source();

        assertTrue(source.contains("getHistoryAsync(code, limit)"));
        assertFalse(source.contains("List<ItemHistory> histories = plugin.getDB().getHistory(code, limit)"));
    }

    private String source() throws Exception {
        return Files.readString(Path.of(
            "src/main/java/com/itemguard/commands/HistoryCommand.java"
        ));
    }
}
