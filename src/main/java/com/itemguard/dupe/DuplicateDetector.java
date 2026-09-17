package com.itemguard.dupe;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class DuplicateDetector {

    public DuplicateAssessment assess(
        UUID itemUuid,
        long scanEpoch,
        boolean epochComplete,
        Collection<ItemObservation> observations
    ) {
        Set<ObservationKey> locations = observations.stream()
            .filter(Objects::nonNull)
            .filter(observation -> itemUuid.equals(observation.itemUuid()))
            .filter(observation -> observation.scanEpoch() == scanEpoch)
            .map(ItemObservation::key)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());

        int distinctLocations = locations.size();
        if (distinctLocations < 2) {
            return new DuplicateAssessment(DuplicateStatus.CLEAN, distinctLocations);
        }
        return new DuplicateAssessment(
            epochComplete ? DuplicateStatus.CONFIRMED : DuplicateStatus.SUSPECTED,
            distinctLocations
        );
    }
}
