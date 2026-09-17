package com.itemguard.services;

import com.itemguard.ConfigManager;
import com.itemguard.ItemGuard;
import com.itemguard.data.DatabaseManager;
import com.itemguard.data.ItemData;
import com.itemguard.identity.PublicItemCodeGenerator;
import com.itemguard.persistence.ItemSqliteRepository;
import com.itemguard.persistence.SqliteConnectionOwner;
import com.itemguard.snapshot.ItemSnapshotCodec;
import com.itemguard.tracking.TagPublication;
import com.itemguard.tracking.TagPublicationState;
import com.itemguard.tracking.TagReconciliationReceipt;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real service/coordinator/JDBC; Bukkit state and Paper serialization are test doubles, not Paper evidence. */
class ItemTrackingPublicationIntegrationTest {
    @TempDir Path temp;

    @Test
    void realCollisionRebuildsSnapshotAndOnlyPublishesFreshIdentity() throws Exception {
        try (var fixture = new Fixture(temp.resolve("retry.db"), new SequenceRandom(0, 1));
             var serialization = mockStatic(ItemStack.class)) {
            serialization.when(() -> ItemStack.deserializeBytes(any(byte[].class))).thenAnswer(call -> fixture.restore(call.getArgument(0)));
            var occupied = canonical("AAAAAA");
            fixture.repository.saveItemWithSnapshot(occupied, new ItemSnapshotCodec(1024).capture(new byte[]{7}), 1L);
            Item entity = fixture.entity();
            assertTrue(fixture.service.requestEntityTag(entity, null));
            assertNull(fixture.service.getCodeFromItem(entity.getItemStack()), "no physical write before reservation");
            fixture.callback(); // typed collision -> fresh proposal
            assertEquals(2, fixture.proposals.size());
            assertNull(fixture.service.getCodeFromItem(entity.getItemStack()));
            fixture.callback(); // fresh reservation -> physical write + canonical publish
            fixture.callback(); // publish completion -> ready
            assertEquals("BBBBBB", fixture.service.getCodeFromItem(entity.getItemStack()));
            assertTrue(fixture.service.isIdentityReady(entity.getItemStack()));
            var first = fixture.proposals.getFirst();
            var fresh = fixture.proposals.getLast();
            assertNotEquals(first.item().getItemUuid(), fresh.item().getItemUuid());
            assertFalse(java.util.Arrays.equals(first.snapshot().payload(), fresh.snapshot().payload()));
            assertTrue(fixture.repository.getTagPublication(first.publicationId()).isEmpty());
            assertEquals(TagPublicationState.PUBLISHED, fixture.repository.getTagPublication(fresh.publicationId()).orElseThrow().state());
            assertEquals(occupied.getItemUuid(), fixture.repository.getItem("AAAAAA").orElseThrow().getItemUuid());
            assertArrayEquals(fresh.snapshot().payload(), entity.getItemStack().serializeAsBytes());
            assertEquals(2, fixture.repository.getStats().getTotalItems());
            assertFalse(fixture.service.requestEntityTag(entity, null), "existing identity must never be regenerated");
            assertEquals(2, fixture.proposals.size());
        }
    }

    @Test
    void exhaustionPreservesPhysicalSourceAndCreatesNoOrphanRows() throws Exception {
        try (var fixture = new Fixture(temp.resolve("exhaust.db"), new SequenceRandom(0))) {
            fixture.repository.saveItemWithSnapshot(canonical("AAAAAA"), new ItemSnapshotCodec(1024).capture(new byte[]{7}), 1L);
            Item entity = fixture.entity();
            ItemStack source = entity.getItemStack();
            assertTrue(fixture.service.requestEntityTag(entity, null));
            fixture.callback(); fixture.callback(); fixture.callback();
            assertEquals(3, fixture.proposals.size());
            assertSame(source, entity.getItemStack());
            for (TagPublication proposal : fixture.proposals) assertTrue(fixture.repository.getTagPublication(proposal.publicationId()).isEmpty());
            assertEquals(1, fixture.repository.getStats().getTotalItems());
            assertTrue(fixture.service.requestEntityTag(entity, null), "exhaustion must release source lock");
            fixture.callback(); fixture.callback(); fixture.callback();
        }
    }

    @Test
    void distinctConcurrentSourcesReserveOnlyOneSharedCodeThenRetry() throws Exception {
        try (var fixture = new Fixture(temp.resolve("concurrent.db"), new SequenceRandom(0, 0, 1));
             var serialization = mockStatic(ItemStack.class)) {
            serialization.when(() -> ItemStack.deserializeBytes(any(byte[].class))).thenAnswer(call -> fixture.restore(call.getArgument(0)));
            Item first = fixture.entity(); Item second = fixture.entity();
            assertTrue(fixture.service.requestEntityTag(first, null));
            assertTrue(fixture.service.requestEntityTag(second, null));
            for (int i = 0; i < 5; i++) fixture.callback();
            assertEquals("AAAAAA", fixture.service.getCodeFromItem(first.getItemStack()));
            assertEquals("BBBBBB", fixture.service.getCodeFromItem(second.getItemStack()));
            assertEquals(2, fixture.repository.getStats().getTotalItems());
            assertEquals(3, fixture.proposals.size());
            assertTrue(fixture.service.isIdentityReady(first.getItemStack()));
            assertTrue(fixture.service.isIdentityReady(second.getItemStack()));
        }
    }

    @Test
    void physicalWriteBeforePublishFailureReconcilesAfterDatabaseReopen() throws Exception {
        Path file = temp.resolve("reopen.db");
        TagPublication prepared;
        try (var fixture = new Fixture(file, new SequenceRandom(2));
             var serialization = mockStatic(ItemStack.class)) {
            serialization.when(() -> ItemStack.deserializeBytes(any(byte[].class))).thenAnswer(call -> fixture.restore(call.getArgument(0)));
            fixture.rejectPublish = true;
            Item entity = fixture.entity();
            assertTrue(fixture.service.requestEntityTag(entity, null));
            fixture.callback(); fixture.callback();
            prepared = fixture.proposals.getFirst();
            assertArrayEquals(prepared.snapshot().payload(), entity.getItemStack().serializeAsBytes());
            assertFalse(fixture.service.isIdentityReady(entity.getItemStack()));
            assertEquals(0, fixture.repository.getStats().getTotalItems());
            assertEquals(TagPublicationState.PREPARED, fixture.repository.getTagPublication(prepared.publicationId()).orElseThrow().state());
        }
        try (var owner = new SqliteConnectionOwner(file)) {
            var repository = new ItemSqliteRepository(owner);
            var receipt = new TagReconciliationReceipt(prepared.item().getCode(), prepared.item().getItemUuid(),
                prepared.sourceKey(), prepared.snapshot().sha256());
            assertTrue(repository.reconcile(receipt, System.currentTimeMillis()).get(5, TimeUnit.SECONDS));
            assertTrue(repository.reconcile(receipt, System.currentTimeMillis()).get(5, TimeUnit.SECONDS));
            assertEquals(1, repository.getStats().getTotalItems());
            assertEquals(TagPublicationState.PUBLISHED, repository.getTagPublication(prepared.publicationId()).orElseThrow().state());
        }
    }

    @Test
    void changedSourceAfterReservationAbortsWithoutPhysicalWriteOrCanonicalRow() throws Exception {
        try (var fixture = new Fixture(temp.resolve("stale.db"), new SequenceRandom(0))) {
            Item entity = fixture.entity();
            ItemStack source = entity.getItemStack();
            assertTrue(fixture.service.requestEntityTag(entity, null));
            when(entity.isValid()).thenReturn(false);
            fixture.callback();
            var proposed = fixture.proposals.getFirst();
            var aborted = fixture.repository.getTagPublication(proposed.publicationId()).orElseThrow();
            assertEquals(TagPublicationState.ABORTED, aborted.state());
            assertEquals("SOURCE_CHANGED", aborted.detail());
            assertSame(source, entity.getItemStack());
            assertEquals(0, fixture.repository.getStats().getTotalItems());
        }
    }

    @Test
    void nonCollisionDatabaseFailureNeverRetriesOrWrites() throws Exception {
        try (var fixture = new Fixture(temp.resolve("outage.db"), new SequenceRandom(0))) {
            fixture.rejectReserve = true;
            Item entity = fixture.entity();
            ItemStack source = entity.getItemStack();
            assertTrue(fixture.service.requestEntityTag(entity, null));
            fixture.callback();
            assertEquals(1, fixture.proposals.size());
            assertSame(source, entity.getItemStack());
            assertEquals(0, fixture.repository.getStats().getTotalItems());
            assertTrue(fixture.repository.getTagPublication(fixture.proposals.getFirst().publicationId()).isEmpty());
        }
    }

    @Test
    void reopenedPreparedSourceReusesReservedIdentityInsteadOfRegeneratingPhysicalItem() throws Exception {
        Path file = temp.resolve("prepared.db");
        Item entity;
        TagPublication prepared;
        try (var fixture = new Fixture(file, new SequenceRandom(0))) {
            entity = fixture.entity();
            assertTrue(fixture.service.requestEntityTag(entity, null));
            prepared = fixture.proposals.getFirst();
            assertEquals(TagPublicationState.PREPARED, fixture.repository.getTagPublication(prepared.publicationId()).orElseThrow().state());
            // No callback dispatched: physical source remains untagged.
            assertNull(fixture.service.getCodeFromItem(entity.getItemStack()));
        }
        try (var fixture = new Fixture(file, new SequenceRandom(1));
             var serialization = mockStatic(ItemStack.class)) {
            var restored = new ItemTrackingCodeGenerationTest.FakeStack();
            restored.values.put(new NamespacedKey("itemguard", "code"), prepared.item().getCode());
            restored.values.put(new NamespacedKey("itemguard", "item_uuid"), prepared.item().getItemUuid().toString());
            when(restored.stack.getAmount()).thenReturn(1); when(restored.stack.getMaxStackSize()).thenReturn(1);
            when(restored.stack.serializeAsBytes()).thenReturn(prepared.snapshot().payload());
            serialization.when(() -> ItemStack.deserializeBytes(any(byte[].class))).thenAnswer(call -> {
                assertArrayEquals(prepared.snapshot().payload(), call.getArgument(0)); return restored.stack;
            });
            assertTrue(fixture.service.requestEntityTag(entity, null));
            fixture.callback(); fixture.callback();
            assertEquals("AAAAAA", fixture.service.getCodeFromItem(entity.getItemStack()));
            assertEquals(prepared.item().getItemUuid(), fixture.service.getItemUuidFromItem(entity.getItemStack()));
            assertEquals("BBBBBB", fixture.proposals.getFirst().item().getCode());
            assertTrue(fixture.repository.getTagPublication(fixture.proposals.getFirst().publicationId()).isEmpty());
            assertEquals(1, fixture.repository.getStats().getTotalItems());
            assertTrue(fixture.service.isIdentityReady(entity.getItemStack()));
        }
    }

    private static ItemData canonical(String code) {
        var data = new ItemData(code, UUID.randomUUID()); data.setMaterial(Material.DIAMOND_SWORD); return data;
    }

    private static final class SequenceRandom extends Random {
        private final int[] values;
        private int position;
        SequenceRandom(int... values) { this.values = values; }
        @Override public int nextInt(int bound) { assertEquals(36, bound); return values[Math.min(position++ / 6, values.length - 1)]; }
    }

    private static final class Fixture implements AutoCloseable {
        final SqliteConnectionOwner owner;
        final ItemSqliteRepository repository;
        final LinkedBlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
        final List<TagPublication> proposals = new ArrayList<>();
        final Map<String, ItemStack> snapshots = new HashMap<>();
        final ItemTrackingService service;
        boolean rejectPublish;
        boolean rejectReserve;

        Fixture(Path file, Random random) {
            owner = new SqliteConnectionOwner(file); repository = new ItemSqliteRepository(owner);
            var plugin = mock(ItemGuard.class); var db = mock(DatabaseManager.class); var configs = mock(ConfigManager.class);
            var server = mock(Server.class); var scheduler = mock(BukkitScheduler.class);
            when(plugin.getDB()).thenReturn(db); when(plugin.getConfigs()).thenReturn(configs);
            when(plugin.getConfig()).thenReturn(new YamlConfiguration()); when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("ItemGuard-offline-test"));
            when(configs.getForceTrackMaterials()).thenReturn(Set.of());
            when(configs.isTrackingEnabled()).thenReturn(true); when(configs.isTrackNonStackable()).thenReturn(true);
            when(configs.isWorldEnabled(anyString())).thenReturn(true);
            when(plugin.getNamespacedKey(anyString())).thenAnswer(call -> new NamespacedKey("itemguard", call.getArgument(0)));
            when(plugin.getServer()).thenReturn(server); when(server.getScheduler()).thenReturn(scheduler);
            doAnswer(call -> { main.add(call.getArgument(1, Runnable.class)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            when(db.reserve(any())).thenAnswer(call -> {
                var proposal = call.getArgument(0, TagPublication.class); proposals.add(proposal);
                return rejectReserve ? CompletableFuture.failedFuture(new IllegalStateException("injected reserve outage")) : repository.reserve(proposal);
            });
            when(db.publish(any(), anyLong())).thenAnswer(call -> rejectPublish
                ? CompletableFuture.failedFuture(new IllegalStateException("injected publish outage"))
                : repository.publish(call.getArgument(0), call.getArgument(1)));
            when(db.abort(any(), anyLong(), anyString())).thenAnswer(call -> repository.abort(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            when(db.reconcile(any(), anyLong())).thenAnswer(call -> repository.reconcile(call.getArgument(0), call.getArgument(1)));
            service = new ItemTrackingService(plugin, new PublicItemCodeGenerator(random));
        }
        Item entity() {
            var entity = mock(Item.class); var world = mock(World.class);
            when(world.getName()).thenReturn("offline"); when(entity.getWorld()).thenReturn(world);
            when(entity.isValid()).thenReturn(true); when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
            var physical = new AtomicReference<>(stack());
            when(entity.getItemStack()).thenAnswer(call -> physical.get());
            doAnswer(call -> { physical.set(call.getArgument(0)); return null; }).when(entity).setItemStack(any());
            return entity;
        }
        ItemStack stack() {
            var fake = new ItemTrackingCodeGenerationTest.FakeStack();
            when(fake.stack.getMaxStackSize()).thenReturn(1); when(fake.stack.getAmount()).thenReturn(1);
            when(fake.stack.clone()).thenAnswer(call -> stack());
            when(fake.stack.serializeAsBytes()).thenAnswer(call -> {
                var sorted = new java.util.TreeMap<String, String>();
                fake.values.forEach((key, value) -> sorted.put(key.toString(), value));
                byte[] payload = sorted.toString().getBytes(StandardCharsets.UTF_8);
                snapshots.put(Base64.getEncoder().encodeToString(payload), fake.stack); return payload;
            });
            return fake.stack;
        }
        ItemStack restore(byte[] bytes) { return java.util.Objects.requireNonNull(snapshots.get(Base64.getEncoder().encodeToString(bytes))); }
        void callback() throws InterruptedException {
            Runnable callback = main.poll(5, TimeUnit.SECONDS); assertNotNull(callback, "expected DB handoff"); callback.run();
        }
        @Override public void close() { owner.close(); }
    }
}
