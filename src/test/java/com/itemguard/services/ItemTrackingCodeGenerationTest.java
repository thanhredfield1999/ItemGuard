package com.itemguard.services;

import com.itemguard.ConfigManager;
import com.itemguard.ItemGuard;
import com.itemguard.data.DatabaseManager;
import com.itemguard.identity.PublicItemCodeGenerator;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ItemTrackingCodeGenerationTest {
    @Test
    void taggedCloneUsesInjectedGeneratorAndLeavesSourceUntouched() throws Exception {
        var plugin = mock(ItemGuard.class);
        var configs = mock(ConfigManager.class);
        when(plugin.getDB()).thenReturn(mock(DatabaseManager.class));
        when(plugin.getConfigs()).thenReturn(configs);
        when(plugin.getConfig()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        when(configs.getForceTrackMaterials()).thenReturn(Set.of());
        when(plugin.getNamespacedKey(anyString())).thenAnswer(call -> new NamespacedKey("itemguard", call.getArgument(0)));
        var random = new CountingRandom();
        var service = new ItemTrackingService(plugin, new PublicItemCodeGenerator(random));
        var source = new FakeStack();
        var clone = new FakeStack();
        when(source.stack.clone()).thenReturn(clone.stack);
        var create = ItemTrackingService.class.getDeclaredMethod("createTaggedClone", ItemStack.class);
        create.setAccessible(true);
        assertSame(clone.stack, create.invoke(service, source.stack));
        assertEquals(6, random.calls, "service must obtain code from tested generator");
        assertEquals("AAAAAA", service.getCodeFromItem(clone.stack));
        assertNotNull(service.getItemUuidFromItem(clone.stack));
        assertTrue(source.values.isEmpty(), "proposal must not tag physical source");
    }

    private static final class CountingRandom extends Random {
        // Public codes are labels; uniqueness is enforced by DB, not this RNG.
        int calls;
        @Override public int nextInt(int bound) { assertEquals(36, bound); calls++; return 0; }
    }

    @Test
    void invalidNegativeLorePositionDoesNotPreventTagging() throws Exception {
        var plugin = mock(ItemGuard.class);
        var configs = mock(ConfigManager.class);
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("uuid-tag.show-on-item", true);
        config.set("uuid-tag.lore-position", -2);
        when(plugin.getDB()).thenReturn(mock(DatabaseManager.class));
        when(plugin.getConfigs()).thenReturn(configs);
        when(plugin.getConfig()).thenReturn(config);
        when(configs.getForceTrackMaterials()).thenReturn(Set.of());
        when(plugin.getNamespacedKey(anyString())).thenAnswer(call -> new NamespacedKey("itemguard", call.getArgument(0)));
        var service = new ItemTrackingService(plugin, new PublicItemCodeGenerator(new CountingRandom()));
        var source = new FakeStack();
        var clone = new FakeStack();
        when(source.stack.clone()).thenReturn(clone.stack);
        when(clone.meta.hasLore()).thenReturn(true);
        when(clone.meta.getLore()).thenReturn(java.util.List.of("existing lore"));
        var create = ItemTrackingService.class.getDeclaredMethod("createTaggedClone", ItemStack.class);
        create.setAccessible(true);
        assertDoesNotThrow(() -> create.invoke(service, source.stack), "invalid lore position must not block identity publication");
        verify(clone.meta).setLore(java.util.List.of("existing lore", "\u00a78#AAAAAA"));
        assertTrue(source.values.isEmpty());
    }

    @Test
    void productionConstructorBuildsServiceUsingSecureRandomGenerator() throws Exception {
        var plugin = mock(ItemGuard.class);
        var configs = mock(ConfigManager.class);
        when(plugin.getDB()).thenReturn(mock(DatabaseManager.class));
        when(plugin.getConfigs()).thenReturn(configs);
        when(configs.getForceTrackMaterials()).thenReturn(Set.of());
        var service = new ItemTrackingService(plugin);
        var generatorField = ItemTrackingService.class.getDeclaredField("codeGenerator");
        generatorField.setAccessible(true);
        var generator = (PublicItemCodeGenerator) generatorField.get(service);
        var randomField = PublicItemCodeGenerator.class.getDeclaredField("random");
        randomField.setAccessible(true);
        assertInstanceOf(java.security.SecureRandom.class, randomField.get(generator));
        assertTrue(generator.generate().matches("[A-Z0-9]{6}"));
    }

    static final class FakeStack {
        final Map<NamespacedKey, String> values = new HashMap<>();
        final ItemStack stack = mock(ItemStack.class);
        final ItemMeta meta = mock(ItemMeta.class);
        final PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        FakeStack() {
            when(stack.getType()).thenReturn(Material.DIAMOND_SWORD);
            when(stack.getItemMeta()).thenReturn(meta);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.has(any(NamespacedKey.class))).thenAnswer(call -> values.containsKey(call.getArgument(0)));
            when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenAnswer(call -> values.containsKey(call.getArgument(0)));
            when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenAnswer(call -> values.get(call.getArgument(0)));
            doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        }
    }
}
