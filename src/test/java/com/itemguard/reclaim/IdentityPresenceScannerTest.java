package com.itemguard.reclaim;

import com.itemguard.identity.IdentityTagResolution;
import com.itemguard.identity.IdentityTagStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdentityPresenceScannerTest {

    private final IdentityPresenceScanner scanner = new IdentityPresenceScanner();
    private final UUID targetUuid = UUID.randomUUID();

    @Test
    void exactCanonicalUuidIsPresent() {
        PresenceStatus status = scanner.scan(
            targetUuid,
            List.of(new IdentityTagResolution(
                IdentityTagStatus.COMPLETE,
                "AB12CD",
                targetUuid
            ))
        );

        assertEquals(PresenceStatus.PRESENT, status);
    }

    @Test
    void sameCodeDifferentUuidAndCorruptTagsAreNotPresent() {
        PresenceStatus status = scanner.scan(
            targetUuid,
            List.of(
                new IdentityTagResolution(
                    IdentityTagStatus.COMPLETE,
                    "AB12CD",
                    UUID.randomUUID()
                ),
                new IdentityTagResolution(
                    IdentityTagStatus.CORRUPT,
                    "AB12CD",
                    null
                )
            )
        );

        assertEquals(PresenceStatus.ABSENT, status);
    }
}
