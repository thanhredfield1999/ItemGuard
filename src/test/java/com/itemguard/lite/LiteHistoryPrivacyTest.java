package com.itemguard.lite;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemHistory;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Privacy regression. LITE scopes a member's history query by tracked_items.owner_uuid, which the
 * writers update to the CURRENT holder. The returned rows are therefore every retained event of that
 * identity, including events recorded while a previous holder carried it. A member must never be able
 * to read a previous holder's account name or exact coordinates without itemguard.history.others.
 */
class LiteHistoryPrivacyTest {

    private static final UUID VIEWER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID PREVIOUS_HOLDER = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final String PREVIOUS_NAME = "VictimPlayer";
    private static final String PREVIOUS_LOCATION = "world (1337, 12, -4242)";
    private static final String CODE = "9PDTWF";

    private static ItemHistory row(String action, UUID playerUuid, String playerName,
                                   String location, long timestamp, long id) {
        ItemHistory history = new ItemHistory();
        history.setId(id);
        history.setCode(CODE);
        history.setItemUuid(UUID.nameUUIDFromBytes((CODE + id).getBytes()));
        history.setAction(action);
        history.setPlayerName(playerName);
        history.setPlayerUuid(playerUuid);
        history.setLocation(location);
        history.setTimestamp(timestamp);
        return history;
    }

    /** Newest first: the viewer picked the item up after a previous holder's recorded base activity. */
    private static List<ItemHistory> windowWithForeignActor() {
        long base = System.currentTimeMillis() - 600_000L;
        return List.of(
            row("PICKUP", VIEWER, "Viewer", "world (0, 64, 0)", base + 60_000L, 2L),
            row("DROP", PREVIOUS_HOLDER, PREVIOUS_NAME, PREVIOUS_LOCATION, base, 1L));
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
        when(player.getUniqueId()).thenReturn(VIEWER);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("itemguard.history")).thenReturn(true);
        when(player.hasPermission("itemguard.history.others")).thenReturn(false);
        return player;
    }

    private static List<String> messages(Player player) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(player, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    @Test void memberDrilldownHidesAnotherPlayersNameAndCoordinates() {
        ItemGuard plugin = plugin();
        Player player = member(plugin);
        when(plugin.getDB().getHistoryByPlayerAsync(VIEWER, 45))
            .thenReturn(CompletableFuture.completedFuture(windowWithForeignActor()));

        new LiteCommand(plugin).onCommand(player, null, "ig", new String[]{"history", "#" + CODE});

        List<String> lines = messages(player);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Dropped")),
            "the foreign event itself must remain visible as an event: " + lines);
        assertFalse(lines.stream().anyMatch(line -> line.contains(PREVIOUS_NAME)),
            "another player's account name must not be disclosed to a member: " + lines);
        assertFalse(lines.stream().anyMatch(line -> line.contains(PREVIOUS_LOCATION)),
            "another player's exact coordinates must not be disclosed to a member: " + lines);
    }

    @Test void memberOverviewHidesAnotherPlayersNameAndCoordinates() {
        ItemGuard plugin = plugin();
        Player player = member(plugin);
        // The newest event of this identity belongs to the previous holder, so the overview row for it
        // is rendered from a foreign actor's record.
        long base = System.currentTimeMillis() - 600_000L;
        when(plugin.getDB().getHistoryByPlayerAsync(VIEWER, 45)).thenReturn(
            CompletableFuture.completedFuture(List.of(
                row("DROP", PREVIOUS_HOLDER, PREVIOUS_NAME, PREVIOUS_LOCATION, base + 60_000L, 2L),
                row("PICKUP", VIEWER, "Viewer", "world (0, 64, 0)", base, 1L))));

        new LiteCommand(plugin).onCommand(player, null, "ig", new String[]{"history"});

        List<String> lines = messages(player);
        assertFalse(lines.stream().anyMatch(line -> line.contains(PREVIOUS_NAME)),
            "overview must not disclose another player's account name: " + lines);
        assertFalse(lines.stream().anyMatch(line -> line.contains(PREVIOUS_LOCATION)),
            "overview must not disclose another player's coordinates: " + lines);
    }

    @Test void staffKeepsFullActorDetailForInvestigation() {
        ItemGuard plugin = plugin();
        Player staff = mock(Player.class);
        when(staff.getUniqueId()).thenReturn(VIEWER);
        when(staff.isOnline()).thenReturn(true);
        when(staff.hasPermission(anyString())).thenReturn(true);
        List<ItemHistory> tracked = windowWithForeignActor();
        when(plugin.getDB().getHistoryAsync(CODE, 45))
            .thenReturn(CompletableFuture.completedFuture(tracked));

        new LiteCommand(plugin).onCommand(staff, null, "ig", new String[]{"search", "#" + CODE});

        List<String> lines = messages(staff);
        assertTrue(lines.stream().anyMatch(line -> line.contains(PREVIOUS_NAME)),
            "staff investigation must keep the actor name: " + lines);
        assertTrue(lines.stream().anyMatch(line -> line.contains(PREVIOUS_LOCATION)),
            "staff investigation must keep the recorded location: " + lines);
    }
}
