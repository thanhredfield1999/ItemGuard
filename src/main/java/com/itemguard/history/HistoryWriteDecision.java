package com.itemguard.history;

/** What the write gate decided for one candidate history row. */
public enum HistoryWriteDecision {

    /** Append a new row: this is a new fact. */
    WRITE,

    /**
     * Fold into the existing row for the same identity, actor and action: the same fact recurring.
     * The event is not lost, it stops being duplicated.
     */
    COALESCE
}
