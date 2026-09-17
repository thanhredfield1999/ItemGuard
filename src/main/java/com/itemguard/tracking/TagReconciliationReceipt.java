package com.itemguard.tracking;

import java.util.Objects;
import java.util.UUID;

public record TagReconciliationReceipt(
    String code,
    UUID itemUuid,
    String sourceKey,
    byte[] taggedDigest
) {
    public TagReconciliationReceipt {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(itemUuid, "itemUuid");
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(taggedDigest, "taggedDigest");
        if (code.isBlank() || sourceKey.isBlank() || sourceKey.length() > 255) {
            throw new IllegalArgumentException("Invalid reconciliation receipt identity");
        }
        if (taggedDigest.length != 32) {
            throw new IllegalArgumentException("Reconciliation receipt digest must be SHA-256");
        }
        taggedDigest = taggedDigest.clone();
    }

    @Override
    public byte[] taggedDigest() {
        return taggedDigest.clone();
    }
}
