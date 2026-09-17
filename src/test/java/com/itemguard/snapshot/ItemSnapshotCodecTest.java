package com.itemguard.snapshot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemSnapshotCodecTest {

    private final ItemSnapshotCodec codec = new ItemSnapshotCodec(1_048_576);

    @Test
    void envelopeRoundTripPreservesExactPayload() {
        byte[] original = "full-fidelity-paper-payload".getBytes(StandardCharsets.UTF_8);

        ItemSnapshot snapshot = codec.capture(original);
        byte[] restored = codec.restore(snapshot);

        assertEquals(ItemSnapshotCodec.CURRENT_SNAPSHOT_VERSION, snapshot.version());
        assertArrayEquals(original, restored);
    }

    @Test
    void rejectsOversizedPayloadBeforePersistence() {
        ItemSnapshotCodec tinyCodec = new ItemSnapshotCodec(8);
        byte[] payload = new byte[9];

        assertThrows(SnapshotValidationException.class, () -> tinyCodec.capture(payload));
    }

    @Test
    void rejectsCorruptChecksumAndFutureVersion() {
        ItemSnapshot valid = codec.capture(new byte[] {1, 2, 3, 4});
        byte[] corruptPayload = valid.payload().clone();
        corruptPayload[0] ^= 0x01;

        assertThrows(SnapshotValidationException.class, () -> codec.restore(
            new ItemSnapshot(valid.version(), corruptPayload, valid.sha256())
        ));
        assertThrows(SnapshotValidationException.class, () -> codec.restore(
            new ItemSnapshot(
                // Version 2 is now a real encoding (Bukkit map, used on servers without
                // Paper's serializeAsBytes), so "current + 1" is no longer a future version.
                // Pick a number well past every known encoding to keep testing fail-closed.
                99,
                valid.payload(),
                valid.sha256()
            )
        ));
        assertTrue(valid.sha256().length == 32);
        assertArrayEquals(valid.payload(), valid.payload());
    }

    @Test
    void snapshotDefensivelyCopiesMutableArrays() {
        ItemSnapshot snapshot = codec.capture(new byte[] {5, 6, 7, 8});
        byte[] first = snapshot.payload();
        byte[] expected = first.clone();
        first[0] ^= 0x01;

        assertArrayEquals(expected, snapshot.payload());
        assertTrue(Arrays.equals(snapshot.sha256(), snapshot.sha256()));
    }
}
