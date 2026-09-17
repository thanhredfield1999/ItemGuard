package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TagReconciliationReceiptTest {

    @Test
    void defensivelyCopiesTaggedDigestOnInputAndOutput() {
        byte[] digest = new byte[32];
        digest[0] = 7;
        TagReconciliationReceipt receipt = new TagReconciliationReceipt(
            "AB12CD",
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "PLAYER_SLOT:owner:8",
            digest
        );

        digest[0] = 9;
        byte[] returned = receipt.taggedDigest();
        returned[0] = 11;

        byte[] expected = new byte[32];
        expected[0] = 7;
        assertArrayEquals(expected, receipt.taggedDigest());
    }

    @Test
    void rejectsMalformedDigestAndPhysicalSourceKey() {
        UUID itemUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        assertThrows(
            IllegalArgumentException.class,
            () -> new TagReconciliationReceipt("AB12CD", itemUuid, "", new byte[32])
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TagReconciliationReceipt(
                "AB12CD",
                itemUuid,
                "PLAYER_SLOT:owner:8",
                new byte[31]
            )
        );
        assertEquals(32, new TagReconciliationReceipt(
            "AB12CD",
            itemUuid,
            "PLAYER_SLOT:owner:8",
            new byte[32]
        ).taggedDigest().length);
    }
}