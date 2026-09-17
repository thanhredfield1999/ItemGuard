package com.itemguard.reclaim;

import com.itemguard.identity.IdentityTagResolution;
import com.itemguard.identity.IdentityTagStatus;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

public final class IdentityPresenceScanner {

    public PresenceStatus scan(
        UUID targetItemUuid,
        Collection<IdentityTagResolution> identities
    ) {
        Objects.requireNonNull(targetItemUuid, "targetItemUuid");
        Objects.requireNonNull(identities, "identities");
        for (IdentityTagResolution identity : identities) {
            if (identity != null
                && identity.status() == IdentityTagStatus.COMPLETE
                && targetItemUuid.equals(identity.itemUuid())) {
                return PresenceStatus.PRESENT;
            }
        }
        return PresenceStatus.ABSENT;
    }
}
