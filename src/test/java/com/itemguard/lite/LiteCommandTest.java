package com.itemguard.lite;

import com.itemguard.ItemGuard;
import com.itemguard.ConfigManager;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiteCommandTest {
    @Test void statsRendersReadableValuesInsteadOfTheJavaObjectIdentity() {
        var messages = renderStats("en", 2, 2);
        assertTrue(messages.stream().anyMatch(message -> message.contains("Tracked items: 7")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("Recorded history: 11")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("Duplicate detections: 2")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("Database: SQLITE (OK)")));
        assertFalse(messages.stream().anyMatch(message -> message.contains("PluginStats@")));
    }

    /**
     * One duplicated item re-detected every scan epoch produced "Duplicate findings: 12", which a
     * server owner reads as twelve duplicated items. The line must carry both the raw detection
     * count and the number of distinct item identities behind it, in both shipped languages.
     */
    @Test void statsSeparatesRawDuplicateDetectionsFromDistinctDuplicatedItems() {
        var english = renderStats("en", 12, 1);
        assertTrue(english.stream().anyMatch(
            message -> message.contains("Duplicate detections: 12 (distinct items: 1)")));
        assertFalse(english.stream().anyMatch(message -> message.contains("Duplicate findings: 12")));

        var vietnamese = renderStats("vi", 12, 1);
        assertTrue(vietnamese.stream().anyMatch(
            message -> message.contains("Phát hiện trùng lặp: 12 (số vật phẩm: 1)")));
    }

    @Test void statsWithoutAnyDuplicateFindingReportsBothCountsAsZero() {
        assertTrue(renderStats("en", 0, 0).stream().anyMatch(
            message -> message.contains("Duplicate detections: 0 (distinct items: 0)")));
        assertTrue(renderStats("vi", 0, 0).stream().anyMatch(
            message -> message.contains("Phát hiện trùng lặp: 0 (số vật phẩm: 0)")));
    }

    private java.util.List<String> renderStats(String language, int detections, int distinctItems) {
        ItemGuard plugin = mock(ItemGuard.class, RETURNS_DEEP_STUBS);
        when(plugin.getConfigs().getLanguage()).thenReturn(language);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("itemguard.stats")).thenReturn(true);
        var scheduler = plugin.getServer().getScheduler();
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        var stats = new com.itemguard.data.PluginStats();
        stats.setTotalItems(7);
        stats.setTotalHistory(11);
        stats.setDuplicatesDetected(detections);
        stats.setDistinctDuplicateItems(distinctItems);
        stats.setDatabaseType("SQLITE");
        stats.setDatabaseStatus("OK");
        when(plugin.getDB().getStatsAsync()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(stats));

        new LiteCommand(plugin).onCommand(sender, null, "ig", new String[]{"stats"});

        var messages = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(messages.capture());
        return messages.getAllValues();
    }

    @Test void synchronousDatabaseFailurePreservesDiagnosticCause() {
        ItemGuard plugin = mock(ItemGuard.class, RETURNS_DEEP_STUBS);
        when(plugin.getConfigs().getLanguage()).thenReturn("en");
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("itemguard.stats")).thenReturn(true);
        var failure = new IllegalStateException("recovery failed");
        when(plugin.getDB().getStatsAsync()).thenThrow(failure);
        new LiteCommand(plugin).onCommand(sender, null, "ig", new String[]{"stats"});
        verify(plugin.getLogger()).log(eq(java.util.logging.Level.WARNING), anyString(), same(failure));
        verify(sender).sendMessage(contains("Database unavailable"));
    }

    @Test void onePendingQueryAndRevokedPermissionSuppressLateResponse() {
        ItemGuard plugin = mock(ItemGuard.class, RETURNS_DEEP_STUBS);
        when(plugin.getConfigs().getLanguage()).thenReturn("en");
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        var future = new java.util.concurrent.CompletableFuture<java.util.List<com.itemguard.data.ItemHistory>>();
        when(plugin.getDB().getHistoryAsync("ABC123", 45)).thenReturn(future);
        var scheduler = plugin.getServer().getScheduler();
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        var command = new LiteCommand(plugin);
        command.onCommand(sender, null, "ig", new String[]{"search", "#ABC123"});
        command.onCommand(sender, null, "ig", new String[]{"search", "#ABC123"});
        verify(plugin.getDB(), times(1)).getHistoryAsync("ABC123", 45);
        verify(sender).sendMessage(contains("in progress"));
        when(sender.hasPermission("itemguard.history.others")).thenReturn(false);
        future.complete(java.util.List.of());
        verify(sender, never()).sendMessage(contains("No recorded history"));
    }

    @Test void previewCancelsClicksAndDragsButNotOrdinaryInventory() throws Exception {
        var constructor = Class.forName("com.itemguard.lite.LiteCommand$OverviewPreview").getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        var holder = (org.bukkit.inventory.InventoryHolder) constructor.newInstance(0);
        var click = mock(org.bukkit.event.inventory.InventoryClickEvent.class, RETURNS_DEEP_STUBS);
        var drag = mock(org.bukkit.event.inventory.InventoryDragEvent.class, RETURNS_DEEP_STUBS);
        when(click.getView().getTopInventory().getHolder()).thenReturn(holder);
        when(drag.getView().getTopInventory().getHolder()).thenReturn(holder);
        var command = new LiteCommand(mock(ItemGuard.class));
        command.onClick(click);
        command.onDrag(drag);
        verify(click).setCancelled(true);
        verify(drag).setCancelled(true);
        var ordinary = mock(org.bukkit.event.inventory.InventoryClickEvent.class, RETURNS_DEEP_STUBS);
        command.onClick(ordinary);
        verify(ordinary, never()).setCancelled(anyBoolean());
    }

    @Test void unsupportedActionsNeverTouchDatabase() {
        ItemGuard plugin = mock(ItemGuard.class);
        ConfigManager config = mock(ConfigManager.class);
        when(plugin.getConfigs()).thenReturn(config);
        when(config.getLanguage()).thenReturn("en");
        CommandSender sender = mock(CommandSender.class);
        new LiteCommand(plugin).onCommand(sender, null, "ig", new String[]{"take", "ABC123"});
        verify(plugin, never()).getDB();
        verify(sender).sendMessage(contains("check | history | search #ID | stats"));
    }
    @Test void unauthorizedHistoryNeverQueriesDatabase() {
        ItemGuard plugin = mock(ItemGuard.class);
        ConfigManager config = mock(ConfigManager.class);
        when(plugin.getConfigs()).thenReturn(config);
        when(config.getLanguage()).thenReturn("en");
        CommandSender sender = mock(CommandSender.class);
        new LiteCommand(plugin).onCommand(sender, null, "ig", new String[]{"history"});
        verify(plugin, never()).getDB();
        verify(sender).sendMessage(contains("permission"));
    }
    @Test void liteCannotEnableOptionalWorldGuardOrDiscordFromOldConfig() {
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.isLiteEdition()).thenReturn(true);
        var yaml = new YamlConfiguration();
        yaml.set("worlds.worldguard-support", true);
        yaml.set("discord.enabled", true);
        yaml.set("anti-dupe.action", "REMOVE_ALL");
        when(plugin.getConfig()).thenReturn(yaml);
        var config = new ConfigManager(plugin);
        assertFalse(config.isWorldGuardEnabled());
        assertFalse(config.isDiscordWebhookEnabled());
        assertEquals("NOTIFY", config.getAntiDupeAction());
        assertEquals("en", config.getLanguage());
    }

    /**
     * LITE is an investigation build: automatic history deletion must be off by code, not merely by
     * the shipped default config. An old or hand-edited config must not re-enable pruning.
     */
    @Test void liteNeverSchedulesAutomaticHistoryDeletion() {
        ItemGuard lite = mock(ItemGuard.class);
        when(lite.isLiteEdition()).thenReturn(true);
        var liteYaml = new YamlConfiguration();
        liteYaml.set("performance.auto-cleanup.interval-hours", 24);
        when(lite.getConfig()).thenReturn(liteYaml);
        assertEquals(0, new ConfigManager(lite).getCleanupIntervalHours());

        ItemGuard full = mock(ItemGuard.class);
        when(full.isLiteEdition()).thenReturn(false);
        var fullYaml = new YamlConfiguration();
        fullYaml.set("performance.auto-cleanup.interval-hours", 24);
        when(full.getConfig()).thenReturn(fullYaml);
        assertEquals(24, new ConfigManager(full).getCleanupIntervalHours());
    }
}
