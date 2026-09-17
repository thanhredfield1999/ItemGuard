package com.itemguard.services;

import com.itemguard.ConfigManager;
import com.itemguard.ItemGuard;
import com.itemguard.data.DatabaseManager;
import com.itemguard.identity.PublicItemCodeGenerator;
import com.itemguard.services.ItemTrackingCodeGenerationTest.FakeStack;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for C2 (review 2026-09-16): an item whose identity is already published must not
 * become permanently unusable after a restart.
 *
 * <p>The readiness cache is in memory, so it is empty after a restart. The only paths that
 * refilled it were the player-inventory and container scans — and those deliberately skip the
 * holders whose physical slot cannot be resolved (ender chests, storage minecarts, virtual
 * inventories). A tagged item sitting in one of those therefore stayed unready forever, and
 * every click on it was cancelled with nothing said. Nothing was wrong with the item or the
 * database; only the in-memory cache had been lost.
 *
 * <p>Reconciliation is asynchronous by design, so the first contact is still refused. The
 * defect is that it was refused *forever* because nothing was ever attempted.
 */
class ItemTrackingReadinessAfterRestartTest {

    @Test
    void contactWithAnAlreadyPublishedIdentityAttemptsReconciliation() throws Exception {
        var plugin = mock(ItemGuard.class);
        var db = mock(DatabaseManager.class);
        var configs = mock(ConfigManager.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        var mainThreadHandoffs = new LinkedBlockingQueue<Runnable>();

        when(plugin.getDB()).thenReturn(db);
        when(plugin.getConfigs()).thenReturn(configs);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ItemGuard-offline-test"));
        when(configs.getForceTrackMaterials()).thenReturn(Set.of());
        when(configs.isTrackingEnabled()).thenReturn(true);
        when(configs.isTrackNonStackable()).thenReturn(true);
        when(configs.isWorldEnabled(anyString())).thenReturn(true);
        when(plugin.getNamespacedKey(anyString()))
            .thenAnswer(call -> new NamespacedKey("itemguard", call.getArgument(0)));
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(call -> {
            mainThreadHandoffs.add(call.getArgument(1, Runnable.class));
            return null;
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));

        // The database still knows this identity: it survived the restart.
        when(db.reconcile(any(), anyLong())).thenReturn(CompletableFuture.completedFuture(true));

        var service = new ItemTrackingService(plugin, new PublicItemCodeGenerator(new Random(1)));
        ItemStack tagged = tagged("ABCDEF");

        assertFalse(
            service.isIdentityReady(tagged),
            "readiness is confirmed asynchronously, so the first contact is refused"
        );

        verify(db).reconcile(any(), anyLong());

        Runnable handoff = mainThreadHandoffs.poll(5, TimeUnit.SECONDS);
        assertNotNull(handoff, "the readiness result must be applied on the main thread");
        handoff.run();

        assertTrue(
            service.isIdentityReady(tagged),
            "after reconciliation the item must be usable again; otherwise every click is cancelled forever"
        );
    }

    /**
     * An item with no identity at all is a different case and must stay out of this path: there
     * is nothing to reconcile, and the caller's job is to tag it, not to look it up.
     */
    @Test
    void untaggedItemNeverTriggersReconciliation() {
        var plugin = mock(ItemGuard.class);
        var db = mock(DatabaseManager.class);
        var configs = mock(ConfigManager.class);

        when(plugin.getDB()).thenReturn(db);
        when(plugin.getConfigs()).thenReturn(configs);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ItemGuard-offline-test"));
        when(configs.getForceTrackMaterials()).thenReturn(Set.of());

        var service = new ItemTrackingService(plugin, new PublicItemCodeGenerator(new Random(2)));
        ItemStack plain = new FakeStack().stack;
        when(plain.getMaxStackSize()).thenReturn(1);
        when(plain.getAmount()).thenReturn(1);

        assertFalse(service.isIdentityReady(plain));

        org.mockito.Mockito.verifyNoInteractions(db);
    }

    private static ItemStack tagged(String code) {
        var fake = new FakeStack();
        fake.values.put(new NamespacedKey("itemguard", "code"), code);
        fake.values.put(new NamespacedKey("itemguard", "item_uuid"), UUID.randomUUID().toString());
        when(fake.stack.getMaxStackSize()).thenReturn(1);
        when(fake.stack.getAmount()).thenReturn(1);
        // Without this the snapshot capture inside the reconcile path throws, the service's own
        // catch swallows it, and the test would pass for the wrong reason.
        when(fake.stack.serializeAsBytes()).thenReturn(new byte[]{7});
        return fake.stack;
    }
}
