package com.itemguard.tracking;

import com.itemguard.data.ItemData;
import com.itemguard.snapshot.ItemSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class TagPublicationCollisionRetryTest {
    @Test
    void typedCollisionRegeneratesOnMainThenPublishesOnlyFreshSnapshot() {
        var fixture = new Fixture();
        var first = publication("AB12CD");
        var fresh = publication("EF34GH");
        fixture.proposals.add(first);
        fixture.proposals.add(fresh);
        assertTrue(fixture.request());
        fixture.pending.remove().completeExceptionally(new CompletionException(new TagIdentityCollisionException()));
        assertEquals(1, fixture.factoryCalls);
        assertNull(fixture.written);
        assertFalse(fixture.request());
        fixture.drain();
        assertEquals(2, fixture.factoryCalls, "retry rebuilds full proposal on dispatcher");
        assertEquals(List.of(first, fresh), fixture.reserved);
        fixture.pending.remove().complete(fresh);
        fixture.drain();
        assertSame(fresh, fixture.written);
        assertSame(fresh.snapshot(), fixture.written.snapshot());
        assertEquals(List.of(fresh.publicationId()), fixture.published);
        assertEquals(0, fixture.failures.size());
    }

    @Test
    void exhaustionIsBoundedAndReleasesSourceLock() {
        var fixture = new Fixture();
        fixture.factory = () -> publication("AB12CD");
        assertTrue(fixture.request());
        for (int attempt = 0; attempt < 3; attempt++) {
            assertFalse(fixture.pending.isEmpty(), "expected reservation attempt " + attempt);
            fixture.pending.remove().completeExceptionally(new TagIdentityCollisionException());
            fixture.drain();
        }
        assertEquals(3, fixture.factoryCalls);
        assertEquals(3, fixture.reserved.size());
        assertEquals(1, fixture.failures.size());
        assertInstanceOf(TagIdentityCollisionException.class, fixture.failures.getFirst());
        assertNull(fixture.written);
        assertTrue(fixture.request(), "lock released after terminal failure");
    }

    @Test
    void changedPhysicalSourceStopsBeforeRegeneration() {
        var fixture = new Fixture();
        fixture.factory = () -> publication("AB12CD");
        assertTrue(fixture.request());
        fixture.matches = false;
        fixture.pending.remove().completeExceptionally(new TagIdentityCollisionException());
        fixture.drain();
        assertEquals(1, fixture.factoryCalls);
        assertEquals(1, fixture.failures.size());
        assertNull(fixture.written);
    }

    @Test
    void disabledPluginStopsBeforeRegeneration() {
        var fixture = new Fixture();
        fixture.factory = () -> publication("AB12CD");
        assertTrue(fixture.request());
        fixture.enabled = false;
        fixture.pending.remove().completeExceptionally(new TagIdentityCollisionException());
        fixture.drain();
        assertEquals(1, fixture.factoryCalls);
        assertTrue(fixture.failures.isEmpty());
        assertNull(fixture.written);
    }

    @Test
    void regeneratedProposalCannotSwitchSourceDigestOrState() {
        for (String defect : new String[] {"source", "digest", "state"}) {
            var fixture = new Fixture();
            fixture.proposals.add(publication("AB12CD"));
            fixture.proposals.add(altered(publication("EF34GH"), defect));
            assertTrue(fixture.request());
            fixture.pending.remove().completeExceptionally(new TagIdentityCollisionException());
            fixture.drain();
            assertEquals(1, fixture.reserved.size(), defect);
            assertEquals(1, fixture.failures.size(), defect);
            assertNull(fixture.written);
        }
    }

    @Test
    void mismatchedSuccessfulReservationNeverWritesPhysicalItem() {
        for (String defect : new String[] {"source", "digest", "state"}) {
            var fixture = new Fixture();
            fixture.proposals.add(publication("AB12CD"));
            assertTrue(fixture.request());
            fixture.pending.remove().complete(altered(publication("EF34GH"), defect));
            fixture.drain();
            assertNull(fixture.written, defect);
            assertTrue(fixture.published.isEmpty(), defect);
            assertEquals(1, fixture.failures.size(), defect);
        }
    }

    @Test
    void genericFailureAndNullResultNeverRetry() {
        for (Throwable failure : new Throwable[] {new IllegalStateException("database error"),
            new IllegalStateException(new TagIdentityCollisionException()), null}) {
            var fixture = new Fixture();
            fixture.factory = () -> publication("AB12CD");
            assertTrue(fixture.request());
            if (failure == null) fixture.pending.remove().complete(null);
            else fixture.pending.remove().completeExceptionally(failure);
            fixture.drain();
            assertEquals(1, fixture.factoryCalls);
            assertEquals(1, fixture.failures.size());
            assertNull(fixture.written);
        }
    }

    @Test
    void throwingFailureCallbackStillReleasesSourceAfterPublishSubmissionFailure() {
        var fixture = new Fixture();
        fixture.factory = () -> publication("AB12CD");
        fixture.publishSubmissionFails = true;
        fixture.publishFailureCallbackThrows = true;
        assertTrue(fixture.request());
        fixture.pending.remove().complete(fixture.reserved.getFirst());
        assertThrows(IllegalStateException.class, fixture::drain);
        fixture.newRequestAfterTerminal = true;
        assertTrue(fixture.request(), "publish failure callback must not leak source lock");
    }

    @Test
    void abortCompletionReleasesSourceLockForChangedOrFailedWrite() {
        for (boolean writeFailure : new boolean[]{false, true}) {
            var fixture = new Fixture();
            fixture.factory = () -> publication("AB12CD");
            fixture.matches = writeFailure;
            fixture.writeThrows = writeFailure;
            fixture.abortResult = new CompletableFuture<>();
            assertTrue(fixture.request());
            fixture.pending.remove().complete(fixture.reserved.getFirst());
            fixture.drain();
            assertFalse(fixture.request(), "keep source locked until abort settles");
            fixture.abortResult.complete(true);
            fixture.newRequestAfterTerminal = true;
            assertTrue(fixture.request(), "abort completion must release lock");
        }
    }

    private static TagPublication altered(TagPublication original, String defect) {
        byte[] digest = original.sourceDigest();
        if (defect.equals("digest")) digest[0] = 1;
        return new TagPublication(original.publicationId(), defect.equals("source") ? "other" : original.sourceKey(),
            digest, original.item(), original.snapshot(), original.capturedAt(),
            defect.equals("state") ? TagPublicationState.PUBLISHED : original.state(),
            original.createdAt(), original.updatedAt(), original.detail());
    }

    private static TagPublication publication(String code) {
        var identity = UUID.randomUUID();
        return new TagPublication(UUID.randomUUID(), "source", new byte[32], new ItemData(code, identity),
            new ItemSnapshot(1, identity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), new byte[32]),
            1L, TagPublicationState.PREPARED, 1L, 1L, null);
    }

    private static final class Fixture implements TagPublicationStore, TagPublicationTarget {
        final Queue<Runnable> main = new ArrayDeque<>();
        final Queue<CompletableFuture<TagPublication>> pending = new ArrayDeque<>();
        final Queue<TagPublication> proposals = new ArrayDeque<>();
        final List<TagPublication> reserved = new ArrayList<>();
        final List<UUID> published = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        Supplier<TagPublication> factory = proposals::remove;
        int factoryCalls;
        boolean enabled = true;
        boolean matches = true;
        boolean onMain;
        boolean publishSubmissionFails;
        boolean publishFailureCallbackThrows;
        boolean newRequestAfterTerminal;
        boolean writeThrows;
        CompletableFuture<Boolean> abortResult = CompletableFuture.completedFuture(true);
        TagPublication written;
        final AsyncTagPublicationCoordinator coordinator = new AsyncTagPublicationCoordinator(
            this, main::add, () -> enabled, () -> 2L);

        boolean request() {
            return coordinator.request(() -> {
                factoryCalls++;
                return factory.get();
            }, this);
        }
        void drain() {
            onMain = true;
            try {
                int remaining = 20;
                while (!main.isEmpty()) {
                    assertTrue(remaining-- > 0, "bounded dispatcher drain");
                    main.remove().run();
                }
            } finally { onMain = false; }
        }
        @Override public String sourceKey() { return "source"; }
        @Override public boolean matches(byte[] digest) { assertTrue(onMain); return matches; }
        @Override public void write(TagPublication publication) {
            assertTrue(onMain);
            if (writeThrows) throw new IllegalStateException("physical write failed");
            written = publication;
        }
        @Override public void preparationFailed(Throwable failure) { assertTrue(onMain); failures.add(failure); }
        @Override public CompletableFuture<TagPublication> reserve(TagPublication proposed) {
            if (!reserved.isEmpty() && failures.isEmpty() && !newRequestAfterTerminal) assertTrue(onMain, "retry reserve dispatched on main");
            reserved.add(proposed);
            var future = new CompletableFuture<TagPublication>();
            pending.add(future);
            return future;
        }
        @Override public CompletableFuture<Boolean> publish(UUID id, long time) {
            if (publishSubmissionFails) throw new IllegalStateException("submission rejected");
            published.add(id); return CompletableFuture.completedFuture(true);
        }
        @Override public void publishFailed(TagPublication publication, Throwable failure) {
            if (publishFailureCallbackThrows) throw new IllegalStateException("callback rejected");
        }
        @Override public CompletableFuture<Boolean> reconcile(TagReconciliationReceipt receipt, long time) {
            throw new AssertionError("reconcile not part of retry");
        }
        @Override public CompletableFuture<Boolean> abort(UUID id, long time, String detail) {
            return abortResult;
        }
    }
}
