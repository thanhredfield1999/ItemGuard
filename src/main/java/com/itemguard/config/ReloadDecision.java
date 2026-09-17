package com.itemguard.config;

import java.util.List;

public record ReloadDecision(
    ReloadStatus status,
    List<String> changedRestartKeys
) {
    public ReloadDecision {
        changedRestartKeys = List.copyOf(changedRestartKeys);
    }
}
