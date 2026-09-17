package com.itemguard.dupe;

import java.util.UUID;

public record ItemObservation(
    UUID itemUuid,
    String code,
    long scanEpoch,
    ObservationKey key,
    long observedAt
) {}
