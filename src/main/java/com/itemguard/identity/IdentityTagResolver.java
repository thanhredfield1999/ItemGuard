package com.itemguard.identity;

import java.util.UUID;

public final class IdentityTagResolver {

    public IdentityTagResolution resolve(String rawCode, String rawUuid) {
        return resolve(rawCode != null, rawCode, rawUuid != null, rawUuid);
    }

    public IdentityTagResolution resolve(
        boolean codeKeyPresent,
        String rawCode,
        boolean uuidKeyPresent,
        String rawUuid
    ) {
        boolean codeMissing = !codeKeyPresent;
        boolean uuidMissing = !uuidKeyPresent;

        if (codeMissing && uuidMissing) {
            return new IdentityTagResolution(IdentityTagStatus.ABSENT, null, null);
        }
        if (codeMissing || uuidMissing || rawCode == null || rawUuid == null
            || rawCode.isBlank()) {
            return new IdentityTagResolution(IdentityTagStatus.CORRUPT, rawCode, null);
        }

        try {
            return new IdentityTagResolution(
                IdentityTagStatus.COMPLETE,
                rawCode,
                UUID.fromString(rawUuid)
            );
        } catch (IllegalArgumentException ignored) {
            return new IdentityTagResolution(IdentityTagStatus.CORRUPT, rawCode, null);
        }
    }

    public IdentityTagResolution resolve(
        boolean codeKeyPresent,
        boolean codeHasStringType,
        String rawCode,
        boolean uuidKeyPresent,
        boolean uuidHasStringType,
        String rawUuid
    ) {
        if ((codeKeyPresent && !codeHasStringType)
            || (uuidKeyPresent && !uuidHasStringType)) {
            return new IdentityTagResolution(IdentityTagStatus.CORRUPT, rawCode, null);
        }
        return resolve(codeKeyPresent, rawCode, uuidKeyPresent, rawUuid);
    }
}
