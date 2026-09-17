package com.itemguard.lite;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import com.itemguard.data.ItemHistorySummary;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Navigation contract: the new chrome slots must route to the right action and stay read-only. */
class LiteMenuNavigationTest {

    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static ItemGuard plugin() {
        ItemGuard plugin = mock(ItemGuard.class, RETURNS_DEEP_STUBS);
        when(plugin.getConfigs().getLanguage()).thenReturn("en");
        var scheduler = plugin.getServer().getScheduler();
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        return plugin;
    }

    private static Player member() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(OWNER);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("itemguard.history")).thenReturn(true);
        return player;
    }

    private static ItemHistorySummary summary(String code) {
        ItemData item = new ItemData(code, UUID.nameUUIDFromBytes(code.getBytes()));
        item.setOwnerUuid(OWNER);
        item.setMaterial(Material.DIAMOND_SWORD);
        item.setItemName("Sword " + code);
        item.setLastSeenAt(System.currentTimeMillis());
        return new ItemHistorySummary(item, 1, Map.of("PICKUP", 1));
    }

    private static ItemHistory row(String code) {
        ItemHistory history = new ItemHistory();
        history.setId(1L);
        history.setCode(code);
        history.setItemUuid(UUID.nameUUIDFromBytes(code.getBytes()));
        history.setAction("PICKUP");
        history.setPlayerName("Owner");
        history.setPlayerUuid(OWNER);
        history.setLocation("world (0, 64, 0)");
        history.setTimestamp(System.currentTimeMillis());
        return history;
    }

    /**
     * Opens the overview with `count` summaries and returns the live holder for click simulation.
     * ItemStack construction is mocked because Bukkit is not running in a unit test.
     */
    private static InventoryHolder open(ItemGuard plugin, Player player, int count, int offset) {
        Inventory inventory = mock(Inventory.class);
        when(plugin.getServer().createInventory(any(InventoryHolder.class), eq(54), anyString()))
            .thenAnswer(call -> {
                InventoryHolder holder = call.getArgument(0);
                when(inventory.getHolder()).thenReturn(holder);
                return inventory;
            });
        var rows = new java.util.ArrayList<ItemHistorySummary>();
        for (int i = 0; i < count; i++) rows.add(summary(String.format("CODE%02d", i)));
        when(plugin.getDB().getHistorySummariesByPlayerAsync(eq(OWNER), anyInt(), eq(offset)))
            .thenReturn(CompletableFuture.completedFuture(List.copyOf(rows)));
        try (var stacks = mockConstruction(org.bukkit.inventory.ItemStack.class, (stack, context) -> {
            when(stack.getItemMeta()).thenReturn(mock(org.bukkit.inventory.meta.ItemMeta.class));
            when(stack.clone()).thenReturn(stack);
        })) {
            new LiteCommand(plugin).onCommand(player, null, "ig", new String[]{"gui"});
        }
        return inventory.getHolder();
    }

    private static InventoryClickEvent clickAt(InventoryHolder holder, Player player, int rawSlot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class, RETURNS_DEEP_STUBS);
        when(event.getView().getTopInventory().getHolder()).thenReturn(holder);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(rawSlot);
        return event;
    }

    @Test void clickingTheCentredPageIndicatorDoesNotCloseOrPage() {
        ItemGuard plugin = plugin();
        Player player = member();
        InventoryHolder holder = open(plugin, player, 2, 0);
        LiteCommand command = new LiteCommand(plugin);
        clearInvocations(plugin.getDB());
        InventoryClickEvent event = clickAt(holder, player, LiteMenuLayout.PAGE_SLOT);
        command.onClick(event);
        verify(event).setCancelled(true);
        verify(player, never()).closeInventory();
        verify(plugin.getDB(), never()).getHistorySummariesByPlayerAsync(any(), anyInt(), anyInt());
    }

    @Test void clickingAContentSlotOpensThatItemsTimeline() {
        ItemGuard plugin = plugin();
        Player player = member();
        InventoryHolder holder = open(plugin, player, 2, 0);
        when(plugin.getDB().getHistoryByPlayerAsync(eq(OWNER), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(List.of(row("CODE00"))));
        clearInvocations(plugin.getDB());
        new LiteCommand(plugin).onClick(clickAt(holder, player, LiteMenuLayout.contentSlots()[0]));
        verify(plugin.getDB()).getHistoryByPlayerAsync(eq(OWNER), anyInt());
    }

    @Test void clickingTheBorderDoesNothingButIsStillCancelled() {
        ItemGuard plugin = plugin();
        Player player = member();
        InventoryHolder holder = open(plugin, player, 2, 0);
        clearInvocations(plugin.getDB());
        InventoryClickEvent event = clickAt(holder, player, 0);
        new LiteCommand(plugin).onClick(event);
        verify(event).setCancelled(true);
        verify(plugin.getDB(), never()).getHistoryByPlayerAsync(any(), anyInt());
        verify(player, never()).closeInventory();
    }

    @Test void disabledPreviousOnTheFirstPageDoesNotRequery() {
        ItemGuard plugin = plugin();
        Player player = member();
        InventoryHolder holder = open(plugin, player, 2, 0);
        clearInvocations(plugin.getDB());
        new LiteCommand(plugin).onClick(clickAt(holder, player, LiteMenuLayout.PREVIOUS_SLOT));
        verify(plugin.getDB(), never()).getHistorySummariesByPlayerAsync(any(), anyInt(), anyInt());
    }

    @Test void nextPageAdvancesByExactlyOnePageOfContentSlots() {
        ItemGuard plugin = plugin();
        Player player = member();
        InventoryHolder holder = open(plugin, player, LiteMenuLayout.PAGE_SIZE + 1, 0);
        clearInvocations(plugin.getDB());
        when(plugin.getDB().getHistorySummariesByPlayerAsync(eq(OWNER), anyInt(), eq(LiteMenuLayout.PAGE_SIZE)))
            .thenReturn(CompletableFuture.completedFuture(List.of()));
        new LiteCommand(plugin).onClick(clickAt(holder, player, LiteMenuLayout.NEXT_SLOT));
        verify(plugin.getDB()).getHistorySummariesByPlayerAsync(eq(OWNER), anyInt(), eq(LiteMenuLayout.PAGE_SIZE));
    }
}
