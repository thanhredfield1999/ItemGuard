package com.itemguard.tracking;

import com.itemguard.data.ItemData;
import com.itemguard.snapshot.ItemSnapshot;

import java.util.Objects;
import java.util.UUID;

public record TagPublication(
    UUID publicationId,
    String sourceKey,
    byte[] sourceDigest,
    ItemData item,
    ItemSnapshot snapshot,
    long capturedAt,
    TagPublicationState state,
    long createdAt,
    long updatedAt,
    String detail
) {
    public TagPublication {
        Objects.requireNonNull(publicationId, "publicationId");
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(sourceDigest, "sourceDigest");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(state, "state");
        if (sourceKey.isBlank() || sourceKey.length() > 255) {
            throw new IllegalArgumentException("Invalid tag publication source key");
        }
        if (sourceDigest.length != 32) {
            throw new IllegalArgumentException("Tag publication source digest must be SHA-256");
        }
        sourceDigest = sourceDigest.clone();
        if (createdAt < 0L || updatedAt < createdAt || capturedAt < 0L) {
            throw new IllegalArgumentException("Invalid tag publication timestamp");
        }
    }

    @Override
    public byte[] sourceDigest() {
        return sourceDigest.clone();
    }
}
