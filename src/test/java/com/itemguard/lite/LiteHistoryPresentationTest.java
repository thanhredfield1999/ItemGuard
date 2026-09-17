package com.itemguard.lite;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemHistory;
import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistorySummary;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Regression for the reported LITE noise: throwing and retrieving a few items produced one raw chat
 * line per recorded event with machine formatting. The recorded events themselves are legitimate and
 * must stay visible on an explicit-ID timeline.
 */
class LiteHistoryPresentationTest {

    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /**
     * Exact live sequence observed by the parent (oldest first): 9 rows, 3 codes, 6 PICKUP / 3 DROP,
     * no duplicated event rows.
     */
    private static List<ItemHistory> liveWindowNewestFirst() {
        long base = System.currentTimeMillis() - 600_000L;
        String[][] sequence = {
            {"9PDTWF", "PICKUP"},
            {"9PDTWF", "DROP"},
            {"9PDTWF", "PICKUP"},
            {"TECYBL", "PICKUP"},
            {"9PDTWF", "DROP"},
            {"TECYBL", "DROP"},
            {"9PDTWF", "PICKUP"},
            {"TECYBL", "PICKUP"},
            {"XJ7MEL", "PICKUP"},
        };
        var rows = new java.util.ArrayList<ItemHistory>();
        for (int i = 0; i < sequence.length; i++) {
            rows.add(row(sequence[i][0], sequence[i][1], base + i * 30_000L, i + 1));
        }
        java.util.Collections.reverse(rows);
        return List.copyOf(rows);
    }

    private static ItemHistory row(String code, String action, long timestamp, long id) {
        ItemHistory history = new ItemHistory();
        history.setId(id);
        history.setCode(code);
        history.setItemUuid(UUID.nameUUIDFromBytes((code + id).getBytes()));
        history.setAction(action);
        history.setPlayerName("Tester");
        history.setPlayerUuid(OWNER);
        history.setLocation("world (10, 64, -5)");
        history.setTimestamp(timestamp);
        return history;
    }

    private static ItemHistorySummary summary(String code, Material material, String name,
                                              int pickup, int drop) {
        ItemData item = new ItemData(code, UUID.nameUUIDFromBytes(code.getBytes()));
        item.setOwnerUuid(OWNER);
        item.setMaterial(material);
        item.setItemName(name);
        item.setLastSeenAt(System.currentTimeMillis());
        return new ItemHistorySummary(item, pickup + drop, Map.of("PICKUP", pickup, "DROP", drop));
    }

    private static ItemGuard plugin() {
        ItemGuard plugin = mock(ItemGuard.class, RETURNS_DEEP_STUBS);
        when(plugin.getConfigs().getLanguage()).thenReturn("en");
        var scheduler = plugin.getServer().getScheduler();
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        return plugin;
    }

    private static Player member(ItemGuard plugin) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(OWNER);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("itemguard.history")).thenReturn(true);
        return player;
    }

    private static List<String> messages(CommandSender sender) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    private static long occurrences(List<String> messages, String needle) {
        return messages.stream().filter(line -> line.contains(needle)).count();
    }

    @Test void ownRecentHistoryShowsOneOverviewRowPerItemInsteadOfEveryEvent() {
        ItemGuard plugin = plugin();
        Player player = member(plugin);
        when(plugin.getDB().getHistoryByPlayerAsync(OWNER, 45))
            .thenReturn(CompletableFuture.completedFuture(liveWindowNewestFirst()));

        new LiteCommand(plugin).onCommand(player, null, "ig", new String[]{"history"});

        List<String> messages = messages(player);
        assertEquals(1, occurrences(messages, "#9PDTWF"), "one overview row per item: " + messages);
        assertEquals(1, occurrences(messages, "#TECYBL"), "one overview row per item: " + messages);
        assertEquals(1, occurrences(messages, "#XJ7MEL"), "one overview row per item: " + messages);
        assertTrue(messages.size() <= 7, "bounded overview, got " + messages.size() + ": " + messages);
    }

    @Test void ownRecentHistoryOverviewCarriesLatestStatusWithoutRawEventCounts() {
        ItemGuard plugin = plugin();
        Player player = member(plugin);
        when(plugin.getDB().getHistoryByPlayerAsync(OWNER, 45))
            .thenReturn(CompletableFuture.completedFuture(liveWindowNewestFirst()));

        new LiteCommand(plugin).onCommand(player, null, "ig", new String[]{"history"});

        List<String> messages = messages(player);
        String tracked = messages.stream().filter(line -> line.contains("#9PDTWF")).findFirst().orElseThrow();
        assertTrue(tracked.contains("Picked up"), "human-readable latest action: " + tracked);
        // A raw per-action count on this row would climb every time the owner drops and re-takes the
        // same item, so the overview shows the latest state only and leaves counting to the timeline.
        assertFalse(tracked.contains("5 events"), "no inflatable raw event count on the row: " + tracked);
        assertTrue(messages.stream().anyMatch(line -> line.contains("/ig history #")),
            "explicit drilldown must be offered: " + messages);
        assertTrue(messages.stream().anyMatch(line ->
                line.toLowerCase(java.util.Locale.ROOT).contains("recent")
                    && line.toLowerCase(java.util.Locale.ROOT).contains("not lifetime")),
            "window must be labelled as recent, not lifetime totals: " + messages);
    }

    @Test void historyOutputUsesReadableTimeInsteadOfRawInstantOrObjectDump() {
        ItemGuard plugin = plugin();
        Player player = member(plugin);
        when(plugin.getDB().getHistoryByPlayerAsync(OWNER, 45))
            .thenReturn(CompletableFuture.completedFuture(liveWindowNewestFirst()));

        new LiteCommand(plugin).onCommand(player, null, "ig", new String[]{"history"});

        List<String> messages = messages(player);
        assertTrue(messages.stream().noneMatch(line -> line.matches(".*\\d{4}-\\d{2}-\\d{2}T.*Z.*")),
            "no raw ISO instant: " + messages);
        assertTrue(messages.stream().noneMatch(line -> line.contains("ItemHistory{")),
            "no object dump: " + messages);
        assertTrue(messages.stream().anyMatch(line -> line.matches(".*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*")),
            "human-readable timestamp expected: " + messages);
    }

    @Test void memberDrilldownOnOwnItemStaysOwnerScopedAndKeepsEveryLegitimateEvent() {
        ItemGuard plugin = plugin();
        Player player = member(plugin);
        when(plugin.getDB().getHistoryByPlayerAsync(OWNER, 45))
            .thenReturn(CompletableFuture.completedFuture(liveWindowNewestFirst()));

        new LiteCommand(plugin).onCommand(player, null, "ig", new String[]{"history", "#9PDTWF"});

        verify(plugin.getDB(), never()).getHistoryAsync(anyString(), anyInt());
        List<String> messages = messages(player);
        assertEquals(0, occurrences(messages, "#TECYBL"), "own drilldown must not expose other items: " + messages);
        assertEquals(0, occurrences(messages, "#XJ7MEL"), "own drilldown must not expose other items: " + messages);
        assertEquals(3, occurrences(messages, "Picked up"), "repeated pickups preserved: " + messages);
        assertEquals(2, occurrences(messages, "Dropped"), "repeated drops preserved: " + messages);
    }

    @Test void memberDrilldownOnForeignCodeRevealsNothingAndNeverQueriesByCode() {
        ItemGuard plugin = plugin();
        Player player = member(plugin);
        when(plugin.getDB().getHistoryByPlayerAsync(OWNER, 45))
            .thenReturn(CompletableFuture.completedFuture(liveWindowNewestFirst()));

        new LiteCommand(plugin).onCommand(player, null, "ig", new String[]{"history", "#ABC123"});

        verify(plugin.getDB(), never()).getHistoryAsync(anyString(), anyInt());
        List<String> messages = messages(player);
        assertTrue(messages.stream().anyMatch(line -> line.contains("No recorded history")), messages.toString());
        assertEquals(0, occurrences(messages, "#9PDTWF"), "no leak of other items: " + messages);
        assertEquals(0, occurrences(messages, "#TECYBL"), "no leak of other items: " + messages);
    }

    @Test void staffExplicitIdKeepsFullTimelineWithReadableLabels() {
        ItemGuard plugin = plugin();
        CommandSender staff = mock(CommandSender.class);
        when(staff.hasPermission(anyString())).thenReturn(true);
        List<ItemHistory> tracked = liveWindowNewestFirst().stream()
            .filter(row -> row.getCode().equals("9PDTWF")).toList();
        when(plugin.getDB().getHistoryAsync("9PDTWF", 45))
            .thenReturn(CompletableFuture.completedFuture(tracked));

        new LiteCommand(plugin).onCommand(staff, null, "ig", new String[]{"search", "#9PDTWF"});

        List<String> messages = messages(staff);
        assertTrue(messages.stream().anyMatch(line -> line.contains("Recent history")), messages.toString());
        assertEquals(3, occurrences(messages, "Picked up"), "no suppression of repeated events: " + messages);
        assertEquals(2, occurrences(messages, "Dropped"), "no suppression of repeated events: " + messages);
        assertTrue(messages.stream().anyMatch(line -> line.toLowerCase(java.util.Locale.ROOT).contains("oldest")),
            "oldest row in the window is not proof of item origin: " + messages);
    }

    @Test void memberCannotUseStaffSearchAndNoQueryIsIssued() {
        ItemGuard plugin = plugin();
        Player player = member(plugin);
        when(player.hasPermission("itemguard.search")).thenReturn(true);

        new LiteCommand(plugin).onCommand(player, null, "ig", new String[]{"search", "#9PDTWF"});

        verify(plugin.getDB(), never()).getHistoryAsync(anyString(), anyInt());
        verify(plugin.getDB(), never()).getHistoryByPlayerAsync(any(), anyInt());
        assertTrue(messages(player).stream().anyMatch(line -> line.contains("Staff permission")),
            messages(player).toString());
    }

    @Test void guiGroupsNineEventsIntoThreeReadonlyIcons() {
        ItemGuard plugin = plugin();
        Player player = member(plugin);
        when(plugin.getDB().getHistorySummariesByPlayerAsync(OWNER, LiteMenuLayout.PAGE_SIZE + 1, 0)).thenReturn(
            CompletableFuture.completedFuture(List.of(
                summary("9PDTWF", Material.DIAMOND_SWORD, "First", 3, 2),
                summary("TECYBL", Material.BOW, "Second", 2, 1),
                summary("XJ7MEL", Material.SHIELD, "Third", 1, 0))));
        var inventory = mock(org.bukkit.inventory.Inventory.class);
        when(plugin.getServer().createInventory(any(org.bukkit.inventory.InventoryHolder.class), eq(54), anyString()))
            .thenReturn(inventory);
        try (var items = mockConstruction(org.bukkit.inventory.ItemStack.class, (item, context) -> {
                when(item.getItemMeta()).thenReturn(mock(org.bukkit.inventory.meta.ItemMeta.class));
                when(item.clone()).thenReturn(item);
            })) {
            var command = new LiteCommand(plugin);
            command.onCommand(player, null, "ig", new String[]{"gui"});
            // Each content slot is written twice by design: the background first, then the tracked
            // item on top. LiteMenuRenderTest asserts the final material per slot.
            int[] content = LiteMenuLayout.contentSlots();
            for (int i = 0; i < 3; i++) {
                verify(inventory, times(2)).setItem(eq(content[i]), any(org.bukkit.inventory.ItemStack.class));
            }
            verify(player).openInventory(inventory);
            var holder = ArgumentCaptor.forClass(org.bukkit.inventory.InventoryHolder.class);
            verify(plugin.getServer()).createInventory(holder.capture(), eq(54), anyString());
            var click = mock(org.bukkit.event.inventory.InventoryClickEvent.class, RETURNS_DEEP_STUBS);
            when(click.getView().getTopInventory().getHolder()).thenReturn(holder.getValue());
            command.onClick(click);
            verify(click).setCancelled(true);
            var drag = mock(org.bukkit.event.inventory.InventoryDragEvent.class, RETURNS_DEEP_STUBS);
            when(drag.getView().getTopInventory().getHolder()).thenReturn(holder.getValue());
            command.onDrag(drag);
            verify(drag).setCancelled(true);
        }
    }

    @Test void guiShowsTheTrackedItemPreviewAndClickOpensThatIdentityTimeline() {
        ItemGuard plugin = plugin();
        Player player = member(plugin);
        var overview = mock(org.bukkit.inventory.Inventory.class);
        var timeline = mock(org.bukkit.inventory.Inventory.class);
        int firstContentSlot = LiteMenuLayout.contentSlots()[0];
        when(plugin.getDB().getHistorySummariesByPlayerAsync(OWNER, LiteMenuLayout.PAGE_SIZE + 1, 0)).thenReturn(
            CompletableFuture.completedFuture(List.of(summary("SWORD01", Material.DIAMOND_SWORD,
                "Audit Sword", 2, 1))));
        when(plugin.getDB().getHistoryByPlayerAsync(OWNER, 45)).thenReturn(
            CompletableFuture.completedFuture(List.of(row("SWORD01", "PICKUP", 1_000L, 1))));
        when(plugin.getServer().createInventory(any(org.bukkit.inventory.InventoryHolder.class), eq(54), anyString()))
            .thenReturn(overview, timeline);

        var previewMaterials = new java.util.ArrayList<Object>();
        try (var items = mockConstruction(org.bukkit.inventory.ItemStack.class, (item, context) ->
                {
                    Object material = context.arguments().isEmpty() ? null : context.arguments().getFirst();
                    previewMaterials.add(material);
                    when(item.getItemMeta()).thenReturn(mock(org.bukkit.inventory.meta.ItemMeta.class));
                    when(item.clone()).thenReturn(item);
                })) {
            var command = new LiteCommand(plugin);
            command.onCommand(player, null, "ig", new String[]{"gui"});
            verify(plugin.getDB()).getHistorySummariesByPlayerAsync(OWNER, LiteMenuLayout.PAGE_SIZE + 1, 0);
            var holder = ArgumentCaptor.forClass(org.bukkit.inventory.InventoryHolder.class);
            verify(plugin.getServer()).createInventory(holder.capture(), eq(54), anyString());
            var click = mock(org.bukkit.event.inventory.InventoryClickEvent.class, RETURNS_DEEP_STUBS);
            when(click.getView().getTopInventory().getHolder()).thenReturn(holder.getValue());
            when(click.getWhoClicked()).thenReturn(player);
            when(click.getRawSlot()).thenReturn(firstContentSlot);

            command.onClick(click);

            assertTrue(previewMaterials.contains(Material.DIAMOND_SWORD),
                "the tracked material must be rendered: " + previewMaterials);
            verify(overview, times(2)).setItem(eq(firstContentSlot), any(org.bukkit.inventory.ItemStack.class));
            verify(player, times(2)).openInventory(any(org.bukkit.inventory.Inventory.class));
            verify(click).setCancelled(true);
        }
    }
}
