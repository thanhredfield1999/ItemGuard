package com.itemguard.tracking;

import com.itemguard.identity.IdentityTagResolution;
import com.itemguard.identity.IdentityTagStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityReadinessCoordinatorTest {

    @Test
    void detachedReadinessCheckNeverSubmitsReconciliation() {
        AtomicInteger submissions = new AtomicInteger();
        IdentityReadinessCoordinator coordinator = new IdentityReadinessCoordinator(
            (receipt, updatedAt) -> {
                submissions.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            },
            Runnable::run,
            () -> 1_000L
        );

        assertFalse(coordinator.isReady(identity()));
        assertFalse(coordinator.isReady(identity()));
        assertTrue(submissions.get() == 0);
    }

    @Test
    void anchoredReceiptMarksIdentityReadyOnlyAfterDurableSuccessOnMainThread() {
        CompletableFuture<Boolean> durable = new CompletableFuture<>();
        Queue<Runnable> main = new ArrayDeque<>();
        IdentityReadinessCoordinator coordinator = new IdentityReadinessCoordinator(
            (receipt, updatedAt) -> durable,
            main::add,
            () -> 1_000L
        );
        IdentityTagResolution identity = identity();
        TagReconciliationReceipt receipt = new TagReconciliationReceipt(
            identity.code(),
            identity.itemUuid(),
            "PLAYER_SLOT:owner:8",
            new byte[32]
        );

        assertFalse(coordinator.reconcile(receipt));
        assertFalse(coordinator.isReady(identity));
        durable.complete(true);
        assertFalse(coordinator.isReady(identity));
        main.remove().run();
        assertTrue(coordinator.isReady(identity));
    }

    @Test
    void failedAnchoredReceiptCanBeRetriedWithoutMarkingReady() {
        AtomicInteger submissions = new AtomicInteger();
        IdentityReadinessCoordinator coordinator = new IdentityReadinessCoordinator(
            (receipt, updatedAt) -> {
                submissions.incrementAndGet();
                return CompletableFuture.completedFuture(false);
            },
            Runnable::run,
            () -> 1_000L
        );
        IdentityTagResolution identity = identity();
        TagReconciliationReceipt receipt = new TagReconciliationReceipt(
            identity.code(),
            identity.itemUuid(),
            "PLAYER_SLOT:owner:8",
            new byte[32]
        );

        assertFalse(coordinator.reconcile(receipt));
        assertFalse(coordinator.reconcile(receipt));
        assertFalse(coordinator.isReady(identity));
        assertTrue(submissions.get() == 2);
    }

    @Test
    void exactReceiptUsesIndependentSingleFlightFromWrongReceipt() {
        CompletableFuture<Boolean> wrong = new CompletableFuture<>();
        Queue<Runnable> main = new ArrayDeque<>();
        AtomicInteger submissions = new AtomicInteger();
        IdentityReadinessCoordinator coordinator = new IdentityReadinessCoordinator(
            (receipt, updatedAt) -> {
                int attempt = submissions.incrementAndGet();
                return attempt == 1
                    ? wrong
                    : CompletableFuture.completedFuture(true);
            },
            main::add,
            () -> 1_000L
        );
        IdentityTagResolution identity = identity();
        TagReconciliationReceipt wrongReceipt = new TagReconciliationReceipt(
            identity.code(),
            identity.itemUuid(),
            "PLAYER_SLOT:owner:5",
            new byte[32]
        );
        TagReconciliationReceipt exactReceipt = new TagReconciliationReceipt(
            identity.code(),
            identity.itemUuid(),
            "PLAYER_SLOT:owner:8",
            new byte[32]
        );

        assertFalse(coordinator.reconcile(wrongReceipt));
        assertFalse(coordinator.reconcile(exactReceipt));
        assertTrue(submissions.get() == 2);
        main.remove().run();
        assertTrue(coordinator.isReady(identity));
        wrong.complete(false);
        main.remove().run();
        assertTrue(coordinator.isReady(identity));
    }

    @Test
    void exactReceiptCannotBeOverwrittenByLaterWrongReceipt() {
        CompletableFuture<Boolean> firstWrong = new CompletableFuture<>();
        Queue<Runnable> main = new ArrayDeque<>();
        AtomicInteger submissions = new AtomicInteger();
        IdentityReadinessCoordinator coordinator = new IdentityReadinessCoordinator(
            (receipt, updatedAt) -> {
                submissions.incrementAndGet();
                return switch (receipt.sourceKey()) {
                    case "PLAYER_SLOT:owner:5" -> firstWrong;
                    case "PLAYER_SLOT:owner:8" -> CompletableFuture.completedFuture(true);
                    default -> CompletableFuture.completedFuture(false);
                };
            },
            main::add,
            () -> 1_000L
        );
        IdentityTagResolution identity = identity();

        assertFalse(coordinator.reconcile(receipt(identity, 5)));
        assertFalse(coordinator.reconcile(receipt(identity, 8)));
        assertFalse(coordinator.reconcile(receipt(identity, 9)));
        firstWrong.complete(false);
        while (!main.isEmpty()) {
            main.remove().run();
        }

        assertTrue(coordinator.isReady(identity));
        assertTrue(submissions.get() >= 2);
    }

    @Test
    void dispatcherFailureNeverMarksIdentityReady() {
        IdentityReadinessCoordinator coordinator = new IdentityReadinessCoordinator(
            (receipt, updatedAt) -> CompletableFuture.completedFuture(true),
            ignored -> {
                throw new IllegalStateException("scheduler closed");
            },
            () -> 1_000L
        );
        IdentityTagResolution identity = identity();

        assertFalse(coordinator.reconcile(receipt(identity, 8)));
        assertFalse(coordinator.isReady(identity));
    }

    private TagReconciliationReceipt receipt(IdentityTagResolution identity, int slot) {
        return new TagReconciliationReceipt(
            identity.code(),
            identity.itemUuid(),
            "PLAYER_SLOT:owner:" + slot,
            new byte[32]
        );
    }

    private IdentityTagResolution identity() {
        return new IdentityTagResolution(
            IdentityTagStatus.COMPLETE,
            "AB12CD",
            UUID.fromString("11111111-1111-1111-1111-111111111111")
        );
    }
}
