package com.itemguard;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemGuardLifecycleContractTest {

    @Test
    void pluginBootstrapUsesTheAssignedBStatsPluginId() throws Exception {
        Field pluginId = ItemGuard.class.getDeclaredField("BSTATS_PLUGIN_ID");
        pluginId.setAccessible(true);
        assertEquals(34029, pluginId.getInt(null));

        boolean ownsMetrics = Arrays.stream(ItemGuard.class.getDeclaredFields())
            .anyMatch(field -> field.getType().getName().equals("org.bstats.bukkit.Metrics"));

        assertTrue(ownsMetrics);
    }
}
