package com.itemguard.dupe;

public record ObservationKey(
    HolderType holderType,
    String holderId,
    int slot
) {}
