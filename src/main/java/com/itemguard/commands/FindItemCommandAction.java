package com.itemguard.commands;

import com.itemguard.search.ItemSearchMode;

import java.time.Duration;

public sealed interface FindItemCommandAction {

    record Start(
        String code,
        ItemSearchMode mode,
        Duration duration
    ) implements FindItemCommandAction {}

    record Stop(String code) implements FindItemCommandAction {}

    record ListActive(int page) implements FindItemCommandAction {}

    record Remove(String code) implements FindItemCommandAction {}

    enum ClearConfirmed implements FindItemCommandAction {
        INSTANCE
    }

    /** Scan diagnostics: the plugin's own numbers rather than server TPS. */
    enum CheckTps implements FindItemCommandAction {
        INSTANCE
    }

    /** Everything stored about one identity: row, history depth, snapshot presence. */
    record InfoItem(String code) implements FindItemCommandAction {}

    /** What a player holds, by the plugin's own records. */
    record InfoPlayer(String playerName) implements FindItemCommandAction {}

    /** The duplicate evidence recorded for one identity. */
    record InfoDupe(String code) implements FindItemCommandAction {}

    /** The finding rows for one identity, with their read state. */
    record ReadFinding(String code) implements FindItemCommandAction {}

    /** Marks a code's unread findings as read, so triage has an end. */
    record AcknowledgeDupe(String code) implements FindItemCommandAction {}
}
