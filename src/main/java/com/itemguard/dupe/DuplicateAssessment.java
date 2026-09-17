package com.itemguard.dupe;

public record DuplicateAssessment(
    DuplicateStatus status,
    int distinctLocations
) {}
