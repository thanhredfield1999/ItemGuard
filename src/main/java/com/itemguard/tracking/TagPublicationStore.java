package com.itemguard.tracking;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface TagPublicationStore {

    CompletableFuture<TagPublication> reserve(TagPublication proposed);

    CompletableFuture<Boolean> publish(UUID publicationId, long updatedAt);

    CompletableFuture<Boolean> reconcile(
        TagReconciliationReceipt receipt,
        long updatedAt
    );

    CompletableFuture<Boolean> abort(
        UUID publicationId,
        long updatedAt,
        String detail
    );
}
