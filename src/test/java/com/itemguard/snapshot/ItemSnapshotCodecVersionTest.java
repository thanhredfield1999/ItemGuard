package com.itemguard.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Snapshots must survive the Spigot/Paper split without breaking existing databases.
 *
 * <p>Version 1 payloads are Paper's {@code ItemStack.serializeAsBytes()} output. Spigot has no
 * such method — a real Spigot 1.21.4 server threw
 * {@code NoSuchMethodError: byte[] org.bukkit.inventory.ItemStack.serializeAsBytes()}
 * and the probe died. Spigot must therefore use a different encoding, which becomes
 * version 2.
 *
 * <p>The constraint that matters: {@code item_snapshots} rows already on disk carry
 * {@code snapshot_version = 1}. A server that upgrades, or one that moves a world between
 * Paper and Spigot, must still be able to read them. Restoring only the current version would
 * silently orphan every snapshot captured before the change — for an evidence plugin that is
 * destroying the evidence.
 */
class ItemSnapshotCodecVersionTest {

    private static final int LIMIT = 1_048_576;

    @Test
    @DisplayName("a version 1 payload still restores after version 2 exists")
    void restoresLegacyVersionOnePayloads() {
        ItemSnapshotCodec codec = new ItemSnapshotCodec(LIMIT);
        byte[] legacyPayload = "paper-serialized-bytes".getBytes(StandardCharsets.UTF_8);

        // Exactly what an older release wrote into item_snapshots.
        ItemSnapshot stored = codec.captureAs(
            ItemSnapshotCodec.SNAPSHOT_VERSION_PAPER_BYTES, legacyPayload);
        assertEquals(1, stored.version());

        assertArrayEquals(legacyPayload, codec.restore(stored));
    }

    @Test
    @DisplayName("a version 2 payload restores too")
    void restoresSpigotVersionTwoPayloads() {
        ItemSnapshotCodec codec = new ItemSnapshotCodec(LIMIT);
        byte[] payload = "bukkit-yaml-serialized".getBytes(StandardCharsets.UTF_8);

        ItemSnapshot stored = codec.captureAs(
            ItemSnapshotCodec.SNAPSHOT_VERSION_BUKKIT_MAP, payload);
        assertEquals(2, stored.version());

        assertArrayEquals(payload, codec.restore(stored));
    }

    @Test
    @DisplayName("an unknown future version is still refused")
    void refusesUnknownVersions() {
        ItemSnapshotCodec codec = new ItemSnapshotCodec(LIMIT);
        byte[] payload = "whatever".getBytes(StandardCharsets.UTF_8);
        ItemSnapshot fromTheFuture = new ItemSnapshot(
            99, payload, codec.captureAs(1, payload).sha256());

        // Fail closed: a payload written by a newer release must not be decoded by guesswork.
        SnapshotValidationException refusal = assertThrows(
            SnapshotValidationException.class, () -> codec.restore(fromTheFuture));
        assertTrue(refusal.getMessage().contains("99"));
    }

    @Test
    @DisplayName("checksum mismatch is still caught on every version")
    void checksumIsVerifiedForBothVersions() {
        ItemSnapshotCodec codec = new ItemSnapshotCodec(LIMIT);
        byte[] payload = "genuine".getBytes(StandardCharsets.UTF_8);

        for (int version : new int[] {1, 2}) {
            ItemSnapshot tampered = new ItemSnapshot(
                version,
                "tampered".getBytes(StandardCharsets.UTF_8),
                codec.captureAs(version, payload).sha256()
            );
            assertThrows(
                SnapshotValidationException.class,
                () -> codec.restore(tampered),
                "version " + version + " must still verify its checksum"
            );
        }
    }

    @Test
    @DisplayName("the two encodings produce different digests for the same item")
    void versionsAreNotInterchangeable() {
        ItemSnapshotCodec codec = new ItemSnapshotCodec(LIMIT);
        byte[] paperBytes = "paper-form".getBytes(StandardCharsets.UTF_8);
        byte[] bukkitBytes = "bukkit-form".getBytes(StandardCharsets.UTF_8);

        // Stated so nobody later assumes a digest can be compared across server platforms:
        // the same physical item snapshotted on Paper and on Spigot yields different bytes,
        // therefore a different sha256. Digests are comparable within one encoding only.
        assertTrue(
            !java.util.Arrays.equals(
                codec.captureAs(1, paperBytes).sha256(),
                codec.captureAs(2, bukkitBytes).sha256()),
            "different encodings must not be assumed to hash alike"
        );
    }
}
