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
}
