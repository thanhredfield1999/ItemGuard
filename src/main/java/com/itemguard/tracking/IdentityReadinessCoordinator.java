package com.itemguard.tracking;

import com.itemguard.identity.IdentityTagResolution;
import com.itemguard.identity.IdentityTagStatus;

import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class IdentityReadinessCoordinator {

    private final Reconciler reconciler;
    private final Consumer<Runnable> mainThreadDispatcher;
    private final LongSupplier clock;
    /**
     * Capped rather than unbounded: this holds one entry per unique item ever reconciled, and
     * a long-running server would otherwise grow it forever. Dropping a cold entry is harmless
     * because readiness is recomputed from the item's own tags on next contact.
     */
    private final BoundedIdentitySet readyIdentities = new BoundedIdentitySet(50_000);
    private final Set<String> inFlightReceipts = ConcurrentHashMap.newKeySet();

    public IdentityReadinessCoordinator(
        Reconciler reconciler,
        Consumer<Runnable> mainThreadDispatcher,
        LongSupplier clock
    ) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.mainThreadDispatcher = Objects.requireNonNull(
            mainThreadDispatcher,
            "mainThreadDispatcher"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean isReady(IdentityTagResolution identity) {
        return identity.status() == IdentityTagStatus.COMPLETE
            && readyIdentities.contains(identityKey(identity));
    }

    public boolean reconcile(TagReconciliationReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        String identityKey = identityKey(receipt.code(), receipt.itemUuid());
        if (readyIdentities.contains(identityKey)) {
            return true;
        }
        String receiptKey = receiptKey(receipt);
        if (!inFlightReceipts.add(receiptKey)) {
            return false;
        }
        final CompletableFuture<Boolean> future;
        try {
            future = Objects.requireNonNull(
                reconciler.reconcile(receipt, clock.getAsLong()),
                "reconciliation future"
            );
        } catch (RuntimeException failure) {
            inFlightReceipts.remove(receiptKey);
            return false;
        }
        future.whenComplete((ready, failure) -> {
            try {
                mainThreadDispatcher.accept(() -> {
                    try {
                        if (failure == null && Boolean.TRUE.equals(ready)) {
                            readyIdentities.add(identityKey);
                        }
                    } finally {
                        inFlightReceipts.remove(receiptKey);
                    }
                });
            } catch (RuntimeException dispatchFailure) {
                inFlightReceipts.remove(receiptKey);
            }
        });
        return false;
    }

    public void markReady(String code, java.util.UUID itemUuid) {
        readyIdentities.add(identityKey(code, itemUuid));
    }

    private String receiptKey(TagReconciliationReceipt receipt) {
        return identityKey(receipt.code(), receipt.itemUuid())
            + ':' + receipt.sourceKey()
            + ':' + HexFormat.of().formatHex(receipt.taggedDigest());
    }

    private String identityKey(IdentityTagResolution identity) {
        return identityKey(identity.code(), identity.itemUuid());
    }

    private String identityKey(String code, java.util.UUID itemUuid) {
        return code + ':' + itemUuid;
    }

    @FunctionalInterface
    public interface Reconciler {
        CompletableFuture<Boolean> reconcile(
            TagReconciliationReceipt receipt,
            long updatedAt
        );
    }
}
