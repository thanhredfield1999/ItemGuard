package com.itemguard.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

/** Evidence returned by a SQLite-to-MySQL migration attempt. */
public record MigrationReport(
    boolean dryRun,
    boolean migrated,
    boolean verified,
    boolean targetEmpty,
    Map<String, Long> sourceRows,
    Map<String, Long> targetRows
) {
    public MigrationReport {
        sourceRows = Map.copyOf(new LinkedHashMap<>(sourceRows));
        targetRows = Map.copyOf(new LinkedHashMap<>(targetRows));
    }
}
