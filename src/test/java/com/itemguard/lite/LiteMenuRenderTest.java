package com.itemguard.lite;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistorySummary;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The browser must render as separated zones with working navigation, not a bare grid of items.
 *
 * <p>Bukkit is not running in a unit test, so real {@code ItemStack.getItemMeta()} cannot be used.
 * Construction is mocked and each stack reports the material it was built with, which is enough to
 * assert which icon landed in which slot.
 */
class LiteMenuRenderTest {

    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static ItemHistorySummary summary(String code) {
        ItemData item = new ItemData(code, UUID.nameUUIDFromBytes(code.getBytes()));
        item.setOwnerUuid(OWNER);
        item.setMaterial(Material.DIAMOND_SWORD);
        item.setItemName("Sword " + code);
        item.setLastSeenAt(System.currentTimeMillis());
        return new ItemHistorySummary(item, 3, Map.of("PICKUP", 2, "DROP", 1));
    }

    /** What ended up in one slot: the material, plus the name and lore that were set on it. */
    private record Painted(Material material, String name, List<String> lore) {
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

    private static Map<Integer, Painted> render(int summaries) {
        ItemGuard plugin = plugin();
        Player player = member(plugin);
        Inventory inventory = mock(Inventory.class);
        when(plugin.getServer().createInventory(any(InventoryHolder.class), anyInt(), anyString()))
            .thenReturn(inventory);
        List<ItemHistorySummary> rows = new ArrayList<>();
        for (int i = 0; i < summaries; i++) {
            rows.add(summary(String.format("CODE%02d", i)));
        }
        when(plugin.getDB().getHistorySummariesByPlayerAsync(eq(OWNER), anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(List.copyOf(rows)));

        Map<ItemStack, Material> materials = new HashMap<>();
        Map<ItemStack, String> names = new HashMap<>();
        Map<ItemStack, List<String>> lores = new HashMap<>();
        try (var stacks = mockConstruction(ItemStack.class, (stack, context) -> {
            Material material = context.arguments().isEmpty() ? Material.AIR
                : (Material) context.arguments().get(0);
            materials.put(stack, material);
            when(stack.getType()).thenReturn(material);
            ItemMeta meta = mock(ItemMeta.class);
            doAnswer(call -> { names.put(stack, call.getArgument(0)); return null; })
                .when(meta).setDisplayName(anyString());
            doAnswer(call -> { lores.put(stack, call.getArgument(0)); return null; })
                .when(meta).setLore(anyList());
            when(stack.getItemMeta()).thenReturn(meta);
            when(stack.clone()).thenReturn(stack);
        })) {
            new LiteCommand(plugin).onCommand(player, null, "ig", new String[]{"gui"});
            ArgumentCaptor<Integer> slots = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<ItemStack> items = ArgumentCaptor.forClass(ItemStack.class);
            verify(inventory, atLeastOnce()).setItem(slots.capture(), items.capture());
            Map<Integer, Painted> painted = new HashMap<>();
            for (int i = 0; i < slots.getAllValues().size(); i++) {
                ItemStack stack = items.getAllValues().get(i);
                painted.put(slots.getAllValues().get(i), new Painted(
                    materials.get(stack),
                    names.getOrDefault(stack, ""),
                    lores.getOrDefault(stack, List.of())));
            }
            return painted;
        }
    }

    @Test void everySlotIsPaintedSoNoRawEmptyHolesRemain() {
        Map<Integer, Painted> painted = render(3);
        for (int slot = 0; slot < LiteMenuLayout.SIZE; slot++) {
            assertNotNull(painted.get(slot), "slot " + slot + " was left unpainted");
        }
    }

    @Test void guideAndCentredPageIndicatorAreAlwaysPresent() {
        Map<Integer, Painted> painted = render(3);
        assertEquals(Material.BOOK, painted.get(LiteMenuLayout.GUIDE_SLOT).material());
        assertEquals(Material.PAPER, painted.get(LiteMenuLayout.PAGE_SLOT).material());
        assertTrue(painted.get(47).material().name().endsWith("GLASS_PANE"),
            "the former close slot is restored to the glass navigation frame");
    }

    @Test void contentGoesIntoTheFramedAreaAndNeverIntoTheBorder() {
        Map<Integer, Painted> painted = render(3);
        int[] content = LiteMenuLayout.contentSlots();
        for (int i = 0; i < 3; i++) {
            assertEquals(Material.DIAMOND_SWORD, painted.get(content[i]).material(),
                "tracked item should occupy content slot " + content[i]);
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot == LiteMenuLayout.GUIDE_SLOT) continue;
            assertTrue(painted.get(slot).material().name().endsWith("GLASS_PANE"),
                "top row outside the guide must be frame, slot " + slot);
        }
    }

    @Test void borderAndContentBackgroundUseDifferentPanes() {
        Map<Integer, Painted> painted = render(0);
        assertEquals(Material.valueOf(LiteMenuChrome.BORDER_MATERIAL), painted.get(0).material());
        int lastContent = LiteMenuLayout.contentSlots()[LiteMenuLayout.PAGE_SIZE - 1];
        assertEquals(Material.valueOf(LiteMenuChrome.CONTENT_BACKGROUND_MATERIAL),
            painted.get(lastContent).material(), "unused content slots use the content background");
    }

    @Test void emptyHistoryStillOpensAGuidedScreenInsteadOfOnlyAChatLine() {
        Map<Integer, Painted> painted = render(0);
        Painted first = painted.get(LiteMenuLayout.contentSlots()[0]);
        assertEquals(Material.LIGHT_GRAY_DYE, first.material(),
            "an empty browser must show a guided empty state");
        assertTrue(String.join(" ", first.lore()).contains("/ig check"),
            "the empty state must offer a concrete next step: " + first.lore());
    }

    @Test void pagingControlsAreLabelledEvenWhenDisabled() {
        Map<Integer, Painted> painted = render(3);
        assertEquals(Material.GRAY_DYE, painted.get(LiteMenuLayout.PREVIOUS_SLOT).material(),
            "first page shows a disabled previous control");
        assertEquals(Material.GRAY_DYE, painted.get(LiteMenuLayout.NEXT_SLOT).material(),
            "a single page shows a disabled next control");
        assertEquals(Material.PAPER, painted.get(LiteMenuLayout.PAGE_SLOT).material());
        assertTrue(painted.get(LiteMenuLayout.PAGE_SLOT).name().contains("1"),
            painted.get(LiteMenuLayout.PAGE_SLOT).name());
    }

    @Test void aFullPageEnablesTheNextControl() {
        Map<Integer, Painted> painted = render(LiteMenuLayout.PAGE_SIZE + 1);
        assertEquals(Material.ARROW, painted.get(LiteMenuLayout.NEXT_SLOT).material(),
            "an overflowing page must offer a next page");
    }

    @Test void itemIconCarriesTheCodeAndTheClickHint() {
        Map<Integer, Painted> painted = render(1);
        Painted icon = painted.get(LiteMenuLayout.contentSlots()[0]);
        assertTrue(icon.name().contains("CODE00"), icon.name());
        String lore = String.join(" ", icon.lore());
        assertTrue(lore.contains(LiteMenuChrome.itemRowHint(false)), lore);
        assertTrue(lore.contains(LiteMenuChrome.historyDisclaimer(false)), lore);
    }
}
