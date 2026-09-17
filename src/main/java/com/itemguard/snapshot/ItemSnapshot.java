package com.itemguard.snapshot;

import java.util.Objects;

public record ItemSnapshot(
    int version,
    byte[] payload,
    byte[] sha256
) {
    public ItemSnapshot {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(sha256, "sha256");
        payload = payload.clone();
        sha256 = sha256.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public byte[] sha256() {
        return sha256.clone();
    }
}
