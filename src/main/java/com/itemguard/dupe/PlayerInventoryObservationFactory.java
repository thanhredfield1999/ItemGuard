package com.itemguard.dupe;

import com.itemguard.identity.IdentityTagResolution;
import com.itemguard.identity.IdentityTagStatus;

import java.util.Optional;
import java.util.UUID;

public final class PlayerInventoryObservationFactory {

    public Optional<ItemObservation> create(
        IdentityTagResolution identity,
        long scanEpoch,
        UUID playerUuid,
        int slot,
        long observedAt
    ) {
        if (identity.status() != IdentityTagStatus.COMPLETE || slot < 0) {
            return Optional.empty();
        }
        return Optional.of(new ItemObservation(
            identity.itemUuid(),
            identity.code(),
            scanEpoch,
            new ObservationKey(HolderType.PLAYER, playerUuid.toString(), slot),
            observedAt
        ));
    }
}
