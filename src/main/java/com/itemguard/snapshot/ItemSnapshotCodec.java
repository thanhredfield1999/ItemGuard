package com.itemguard.snapshot;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public final class ItemSnapshotCodec {

    /**
     * Paper's {@code ItemStack.serializeAsBytes()} output. The original and only format until
     * Spigot support was added; every snapshot written before then carries this version.
     */
    public static final int SNAPSHOT_VERSION_PAPER_BYTES = 1;

    /**
     * Bukkit's {@code ConfigurationSerializable} map, rendered to YAML bytes. Used where
     * {@code serializeAsBytes()} does not exist — Spigot throws
     * {@code NoSuchMethodError} for it.
     */
    public static final int SNAPSHOT_VERSION_BUKKIT_MAP = 2;

    public static final int CURRENT_SNAPSHOT_VERSION = SNAPSHOT_VERSION_PAPER_BYTES;

    private final int maximumPayloadBytes;

    public ItemSnapshotCodec(int maximumPayloadBytes) {
        if (maximumPayloadBytes < 1) {
            throw new IllegalArgumentException("Maximum snapshot payload must be positive");
        }
        this.maximumPayloadBytes = maximumPayloadBytes;
    }

    public ItemSnapshot capture(byte[] payload) {
        return captureAs(CURRENT_SNAPSHOT_VERSION, payload);
    }

    /** Captures a payload under an explicit encoding version. */
    public ItemSnapshot captureAs(int version, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        validatePayloadSize(payload);
        return new ItemSnapshot(version, payload, sha256(payload));
    }

    public byte[] restore(ItemSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        // Both known encodings must stay readable. Rows written by an older release carry
        // version 1, and a server that switches platform (or upgrades) must not lose them:
        // refusing to decode stored evidence would destroy the very thing this plugin keeps.
        // Unknown versions still fail closed — a payload from a newer release must never be
        // decoded by guesswork.
        if (snapshot.version() != SNAPSHOT_VERSION_PAPER_BYTES
            && snapshot.version() != SNAPSHOT_VERSION_BUKKIT_MAP) {
            throw new SnapshotValidationException(
                "Unsupported item snapshot version: " + snapshot.version()
            );
        }
        byte[] payload = snapshot.payload();
        validatePayloadSize(payload);
        byte[] expectedChecksum = snapshot.sha256();
        if (expectedChecksum.length != 32
            || !MessageDigest.isEqual(expectedChecksum, sha256(payload))) {
            throw new SnapshotValidationException("Item snapshot checksum mismatch");
        }
        return payload;
    }

    private void validatePayloadSize(byte[] payload) {
        if (payload.length == 0) {
            throw new SnapshotValidationException("Item snapshot payload is empty");
        }
        if (payload.length > maximumPayloadBytes) {
            throw new SnapshotValidationException(
                "Item snapshot payload exceeds " + maximumPayloadBytes + " bytes"
            );
        }
    }

    private byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
