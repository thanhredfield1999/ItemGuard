package com.itemguard.lite;

import com.itemguard.ItemGuard;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiteReleaseBoundaryTest {
    @Test void wildcardOperatorCannotDispatchRestoreOrTeleport() {
        var plugin = mock(ItemGuard.class, RETURNS_DEEP_STUBS);
        when(plugin.getConfigs().getLanguage()).thenReturn("en");
        var sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        var command = new LiteCommand(plugin);
        for (String action : new String[]{"restore", "teleport"}) {
            assertTrue(command.onCommand(sender, null, "ig", new String[]{action, "#ABC123", "Player", "reason"}));
        }
        verify(plugin, never()).getDB();
        verify(plugin, never()).getServer();
        var suggestions = command.onTabComplete(sender, null, "ig", new String[]{""});
        assertEquals(java.util.List.of("check", "history", "search", "stats", "gui"), suggestions);
        verify(sender, never()).sendMessage(contains("restore"));
        verify(sender, never()).sendMessage(contains("teleport"));
    }

    @Test void timelineContentClickHasNoPlayerOrWorldSideEffects() throws Exception {
        var constructor = Class.forName("com.itemguard.lite.LiteCommand$TimelinePreview").getDeclaredConstructor();
        constructor.setAccessible(true);
        var holder = (InventoryHolder) constructor.newInstance();
        var field = holder.getClass().getDeclaredField("rows");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var rows = (java.util.Map<Integer, com.itemguard.data.ItemHistory>) field.get(holder);
        rows.put(10, mock(com.itemguard.data.ItemHistory.class));
        var click = mock(InventoryClickEvent.class, RETURNS_DEEP_STUBS);
        var player = mock(Player.class);
        when(click.getView().getTopInventory().getHolder()).thenReturn(holder);
        when(click.getWhoClicked()).thenReturn(player);
        when(click.getRawSlot()).thenReturn(10);
        when(click.isLeftClick()).thenReturn(true);
        var plugin = mock(ItemGuard.class);
        new LiteCommand(plugin).onClick(click);
        verify(click).setCancelled(true);
        verifyNoInteractions(plugin, player);
    }
}
