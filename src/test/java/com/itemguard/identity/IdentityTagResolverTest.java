package com.itemguard.identity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IdentityTagResolverTest {

    private final IdentityTagResolver resolver = new IdentityTagResolver();

    @Test
    void bothTagsMissingMeansAbsent() {
        IdentityTagResolution result = resolver.resolve(null, null);

        assertEquals(IdentityTagStatus.ABSENT, result.status());
        assertNull(result.code());
        assertNull(result.itemUuid());
    }

    @Test
    void validCodeAndUuidMeanComplete() {
        UUID uuid = UUID.randomUUID();

        IdentityTagResolution result = resolver.resolve("AB12CD", uuid.toString());

        assertEquals(IdentityTagStatus.COMPLETE, result.status());
        assertEquals("AB12CD", result.code());
        assertEquals(uuid, result.itemUuid());
    }

    @Test
    void missingUuidMeansCorrupt() {
        IdentityTagResolution result = resolver.resolve("AB12CD", null);

        assertEquals(IdentityTagStatus.CORRUPT, result.status());
    }

    @Test
    void missingCodeMeansCorrupt() {
        IdentityTagResolution result = resolver.resolve(null, UUID.randomUUID().toString());

        assertEquals(IdentityTagStatus.CORRUPT, result.status());
    }

    @Test
    void blankCodeMeansCorrupt() {
        IdentityTagResolution result = resolver.resolve("  ", UUID.randomUUID().toString());

        assertEquals(IdentityTagStatus.CORRUPT, result.status());
    }

    @Test
    void malformedUuidMeansCorrupt() {
        IdentityTagResolution result = resolver.resolve("AB12CD", "not-a-uuid");

        assertEquals(IdentityTagStatus.CORRUPT, result.status());
    }

    @Test
    void keysPresentWithWrongPersistentDataTypeFailClosed() {
        assertEquals(
            IdentityTagStatus.CORRUPT,
            resolver.resolve(true, null, true, null).status()
        );
        assertEquals(
            IdentityTagStatus.CORRUPT,
            resolver.resolve(true, null, false, null).status()
        );
        assertEquals(
            IdentityTagStatus.CORRUPT,
            resolver.resolve(false, null, true, null).status()
        );
        assertEquals(
            IdentityTagStatus.CORRUPT,
            resolver.resolve(true, null, true, UUID.randomUUID().toString()).status()
        );
        assertEquals(
            IdentityTagStatus.CORRUPT,
            resolver.resolve(true, "AB12CD", true, null).status()
        );
    }

    @Test
    void wrongPersistentDataTypeIsRejectedBeforeTypedRead() {
        assertEquals(
            IdentityTagStatus.CORRUPT,
            resolver.resolve(true, false, null, true, false, null).status()
        );
        assertEquals(
            IdentityTagStatus.CORRUPT,
            resolver.resolve(true, true, "AB12CD", true, false, null).status()
        );
    }
}
