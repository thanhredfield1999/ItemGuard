package com.itemguard.data;

import java.util.Map;
import java.util.Objects;

/**
 * Read-only retained-history totals for one tracked identity.
 * Counts deliberately describe persisted rows, not live custody or lifetime history outside retention.
 */
public record ItemHistorySummary(ItemData item, int totalEvents, Map<String, Integer> actionCounts) {
    public ItemHistorySummary {
        item = Objects.requireNonNull(item, "item");
        if (totalEvents < 0) throw new IllegalArgumentException("totalEvents");
        actionCounts = Map.copyOf(actionCounts);
    }
}
