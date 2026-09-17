package com.itemguard.dupe;

import com.itemguard.identity.IdentityTagResolution;
import com.itemguard.identity.IdentityTagStatus;

import java.util.Optional;
import java.util.UUID;

public final class ContainerInventoryObservationFactory {

    public Optional<ItemObservation> create(
        IdentityTagResolution identity,
        long scanEpoch,
        UUID worldUuid,
        int blockX,
        int blockY,
        int blockZ,
        int slot,
        long observedAt
    ) {
        if (identity.status() != IdentityTagStatus.COMPLETE
            || worldUuid == null
            || slot < 0) {
            return Optional.empty();
        }
        String holderId = "BLOCK:" + worldUuid + ':'
            + blockX + ':' + blockY + ':' + blockZ;
        return Optional.of(new ItemObservation(
            identity.itemUuid(),
            identity.code(),
            scanEpoch,
            new ObservationKey(HolderType.CONTAINER, holderId, slot),
            observedAt
        ));
    }
}
