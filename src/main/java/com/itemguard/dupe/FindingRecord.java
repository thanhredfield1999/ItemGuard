package com.itemguard.dupe;

/**
 * One row of {@code duplicate_findings} as an admin command reads it.
 *
 * <p>Deliberately separate from {@link DuplicateFinding}: that record is the epoch audit's product
 * (identity, status, action) and every field in it is produced by detection. This one carries the
 * storage columns a triage view needs — the row id, the recorded detail, and who marked it read —
 * and {@code acknowledgedAt} is null both for an unread finding and on a backend that does not store
 * acknowledgement, which the reader distinguishes through the command's own output rather than by
 * guessing here.
 */
public record FindingRecord(
    long findingId,
    String code,
    String itemUuid,
    long scanEpoch,
    String status,
    int distinctLocations,
    String action,
    long createdAt,
    String detail,
    Long acknowledgedAt,
    String acknowledgedBy
) {
    public boolean acknowledged() {
        return acknowledgedAt != null;
    }
}
