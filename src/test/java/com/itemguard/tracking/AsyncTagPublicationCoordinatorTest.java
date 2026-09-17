package com.itemguard.tracking;

import com.itemguard.data.ItemData;
import com.itemguard.snapshot.ItemSnapshot;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTagPublicationCoordinatorTest {

    @Test
    void physicalWriteRunsOnDispatcherAfterReserveAndBeforeCanonicalPublish() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(order);
        Queue<Runnable> main = new ArrayDeque<>();
        AtomicBoolean enabled = new AtomicBoolean(true);
        AsyncTagPublicationCoordinator coordinator = new AsyncTagPublicationCoordinator(
            store,
            main::add,
            enabled::get,
            System::currentTimeMillis
        );
        TagPublication publication = publication("source-1");
        TestTarget target = new TestTarget(true, order);

        assertTrue(coordinator.request(() -> publication, target));
        assertFalse(target.written);
        assertTrue(main.isEmpty());

        store.reserveFuture.complete(publication);
        assertEquals(1, main.size());
        main.remove().run();

        assertTrue(target.written);
        assertEquals(List.of("physical", "publish"), order);
    }

    @Test
    void changedSourceAbortsWithoutPhysicalWrite() {
        FakeStore store = new FakeStore(new ArrayList<>());
        Queue<Runnable> main = new ArrayDeque<>();
        AsyncTagPublicationCoordinator coordinator = new AsyncTagPublicationCoordinator(
            store,
            main::add,
            () -> true,
            System::currentTimeMillis
        );
        TagPublication publication = publication("source-1");
        TestTarget target = new TestTarget(false, new ArrayList<>());

        coordinator.request(() -> publication, target);
        store.reserveFuture.complete(publication);
        main.remove().run();

        assertFalse(target.written);
        assertEquals(1, store.aborts);
        assertEquals(0, store.publishes);
    }

    @Test
    void shutdownRetainsPreparedWithoutTouchingSource() {
        FakeStore store = new FakeStore(new ArrayList<>());
        Queue<Runnable> main = new ArrayDeque<>();
        AtomicBoolean enabled = new AtomicBoolean(true);
        AsyncTagPublicationCoordinator coordinator = new AsyncTagPublicationCoordinator(
            store,
            main::add,
            enabled::get,
            System::currentTimeMillis
        );
        TagPublication publication = publication("source-1");
        TestTarget target = new TestTarget(true, new ArrayList<>());

        coordinator.request(() -> publication, target);
        store.reserveFuture.complete(publication);
        enabled.set(false);
        main.remove().run();

        assertFalse(target.written);
        assertEquals(0, store.aborts);
        assertEquals(0, store.publishes);
    }

    @Test
    void duplicateInFlightSourceIsCoalesced() {
        FakeStore store = new FakeStore(new ArrayList<>());
        AsyncTagPublicationCoordinator coordinator = new AsyncTagPublicationCoordinator(
            store,
            runnable -> {},
            () -> true,
            System::currentTimeMillis
        );
        TagPublication publication = publication("source-1");

        assertTrue(coordinator.request(
            () -> publication,
            new TestTarget(true, new ArrayList<>())
        ));
        assertFalse(coordinator.request(
            () -> publication,
            new TestTarget(true, new ArrayList<>())
        ));
        assertEquals(1, store.reserves);
    }

    @Test
    void distinctPhysicalSourcesPublishIndependentlyWhenCompletionsReverse() {
        MultiSourceStore store = new MultiSourceStore();
        Queue<Runnable> main = new ArrayDeque<>();
        List<String> order = new ArrayList<>();
        AsyncTagPublicationCoordinator coordinator = new AsyncTagPublicationCoordinator(
            store,
            main::add,
            () -> true,
            System::currentTimeMillis
        );
        String leftSource = "BLOCK_CONTAINER_SLOT:world:10:64:10:3";
        String rightSource = "BLOCK_CONTAINER_SLOT:world:11:64:10:3";
        TagPublication left = publication(leftSource);
        TagPublication right = publication(rightSource);

        assertTrue(coordinator.request(
            () -> left,
            new TestTarget(leftSource, true, order)
        ));
        assertTrue(coordinator.request(
            () -> right,
            new TestTarget(rightSource, true, order)
        ));
        assertEquals(2, store.reserves);

        store.completeReserve(right);
        store.completeReserve(left);
        assertEquals(2, main.size());
        main.remove().run();
        main.remove().run();
        while (!main.isEmpty()) {
            main.remove().run();
        }

        assertEquals(2, store.publishes);
        assertEquals(List.of(
            "physical:" + rightSource,
            "physical:" + leftSource
        ), order);
    }

    @Test
    void reserveFailureIsReportedOnMainThread() {
        FakeStore store = new FakeStore(new ArrayList<>());
        Queue<Runnable> main = new ArrayDeque<>();
        AsyncTagPublicationCoordinator coordinator = new AsyncTagPublicationCoordinator(
            store,
            main::add,
            () -> true,
            System::currentTimeMillis
        );
        TestTarget target = new TestTarget(true, new ArrayList<>());

        assertTrue(coordinator.request(() -> publication("source-1"), target));
        store.reserveFuture.completeExceptionally(new IllegalStateException("reserve failed"));

        assertEquals(1, main.size());
        assertEquals(0, target.preparationFailures);
        main.remove().run();
        assertEquals(1, target.preparationFailures);
    }

    private TagPublication publication(String sourceKey) {
        ItemData item = new ItemData("AB12CD", UUID.randomUUID());
        item.setMaterial(Material.DIAMOND_SWORD);
        item.setCreatedAt(1L);
        item.setLastSeenAt(1L);
        return new TagPublication(
            UUID.randomUUID(), sourceKey, new byte[32], item,
            new ItemSnapshot(1, new byte[] {1}, new byte[32]),
            1L, TagPublicationState.PREPARED, 1L, 1L, null
        );
    }

    private static final class TestTarget implements TagPublicationTarget {
        private final String sourceKey;
        private final boolean matches;
        private final List<String> order;
        private boolean written;
        private int preparationFailures;

        private TestTarget(boolean matches, List<String> order) {
            this("source-1", matches, order);
        }

        private TestTarget(String sourceKey, boolean matches, List<String> order) {
            this.sourceKey = sourceKey;
            this.matches = matches;
            this.order = order;
        }

        @Override
        public String sourceKey() {
            return sourceKey;
        }

        @Override
        public boolean matches(byte[] expectedDigest) {
            return matches;
        }

        @Override
        public void write(TagPublication publication) {
            written = true;
            order.add(sourceKey.equals("source-1")
                ? "physical"
                : "physical:" + sourceKey);
        }

        @Override
        public void preparationFailed(Throwable failure) {
            preparationFailures++;
        }
    }

    private static final class MultiSourceStore implements TagPublicationStore {
        private final Map<String, CompletableFuture<TagPublication>> reserveFutures =
            new HashMap<>();
        private int reserves;
        private int publishes;

        @Override
        public CompletableFuture<TagPublication> reserve(TagPublication proposed) {
            reserves++;
            CompletableFuture<TagPublication> future = new CompletableFuture<>();
            reserveFutures.put(proposed.sourceKey(), future);
            return future;
        }

        private void completeReserve(TagPublication publication) {
            reserveFutures.get(publication.sourceKey()).complete(publication);
        }

        @Override
        public CompletableFuture<Boolean> publish(UUID publicationId, long updatedAt) {
            publishes++;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> reconcile(
            TagReconciliationReceipt receipt,
            long updatedAt
        ) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> abort(
            UUID publicationId,
            long updatedAt,
            String detail
        ) {
            return CompletableFuture.completedFuture(true);
        }
    }

    private static final class FakeStore implements TagPublicationStore {
        private final CompletableFuture<TagPublication> reserveFuture = new CompletableFuture<>();
        private final List<String> order;
        private int reserves;
        private int publishes;
        private int aborts;

        private FakeStore(List<String> order) {
            this.order = order;
        }

        @Override
        public CompletableFuture<TagPublication> reserve(TagPublication proposed) {
            reserves++;
            return reserveFuture;
        }

        @Override
        public CompletableFuture<Boolean> publish(UUID publicationId, long updatedAt) {
            publishes++;
            order.add("publish");
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> reconcile(
            TagReconciliationReceipt receipt,
            long updatedAt
        ) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> abort(
            UUID publicationId,
            long updatedAt,
            String detail
        ) {
            aborts++;
            return CompletableFuture.completedFuture(true);
        }
    }
}
