package com.itemguard.history;

import com.itemguard.data.ItemHistory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Decides whether a history event is a new fact or the same fact repeating.
 *
 * <p>Rationale in {@code docs/design/2026-09-12-history-write-amplification.md}. Without this, a
 * player holding the drop key writes two permanent rows per second: storage grows without bound, real
 * evidence is pushed out of every bounded query window, and a gameplay path pays for a database write
 * per keypress. Filtering at render time does not help, because the rows are already on disk.
 *
 * <p>Suppression is deliberately narrow. A row is only folded when the item identity, the actor and
 * the action are all unchanged, custody has not moved since that action was last seen, and the repeat
 * is inside the window. Anything an investigation depends on — a different player, a different
 * action, a different item, or a long gap — always writes.
 *
 * <p>Per-action recency matters: a throw-and-retake loop alternates DROP and PICKUP, so each event
 * differs from the one immediately before it. Comparing only against the previous event would never
 * suppress anything, which is precisely how the first attempt at this failed.
 *
 * <p>Whenever the acting player changes, the remembered actions for that item are discarded. Custody
 * moving is a hard boundary: after it, the same action by an earlier holder is new activity, not a
 * repeat.
 *
 * <p>State is held in a bounded, access-ordered map so that touching many distinct items cannot turn
 * the protection itself into a memory leak.
 */
public final class HistoryWriteGate {

    /** Default suppression window: repeats of one action by one holder inside this are one fact. */
    public static final long DEFAULT_WINDOW_MILLIS = 60_000L;

    /**
     * Ceiling on rows one item+actor+action combination may occupy.
     *
     * <p>The time window alone only bounds the rate. An attacker pacing events just above the window
     * would still write one row each, forever, which is enough to push a real handover out of a
     * bounded query window — threat T2 in the design note. Past this many rows the combination is
     * always folded, so a single behaviour cannot dominate an item's history.
     */
    public static final int DEFAULT_MAX_ROWS_PER_COMBINATION = 20;

    /** Default cap on remembered item identities; least recently used entries are evicted first. */
    public static final int DEFAULT_CAPACITY = 4_096;

    /** Defensive cap on distinct actions remembered per item. */
    private static final int MAX_ACTIONS_PER_ITEM = 32;

    private final long windowMillis;
    private final int capacity;
    private final int maxRowsPerCombination;
    private final Map<UUID, ItemState> states;

    public HistoryWriteGate(long windowMillis) {
        this(windowMillis, DEFAULT_CAPACITY);
    }

    public HistoryWriteGate(long windowMillis, int capacity) {
        this(windowMillis, capacity, DEFAULT_MAX_ROWS_PER_COMBINATION);
    }

    public HistoryWriteGate(long windowMillis, int capacity, int maxRowsPerCombination) {
        if (windowMillis < 0) {
            throw new IllegalArgumentException("windowMillis must not be negative");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (maxRowsPerCombination < 1) {
            throw new IllegalArgumentException("maxRowsPerCombination must be positive");
        }
        this.windowMillis = windowMillis;
        this.capacity = capacity;
        this.maxRowsPerCombination = maxRowsPerCombination;
        this.states = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<UUID, ItemState> eldest) {
                return size() > HistoryWriteGate.this.capacity;
            }
        };
    }

    /**
     * @param candidate the row about to be written; null or incomplete rows always write, so this
     *                  gate can never silently discard data it does not understand
     */
    public synchronized HistoryWriteDecision evaluate(ItemHistory candidate) {
        if (candidate == null
            || candidate.getItemUuid() == null
            || candidate.getPlayerUuid() == null
            || candidate.getAction() == null) {
            return HistoryWriteDecision.WRITE;
        }
        UUID item = candidate.getItemUuid();
        UUID actor = candidate.getPlayerUuid();
        String action = candidate.getAction().toUpperCase(java.util.Locale.ROOT);
        long at = candidate.getTimestamp();

        ItemState state = states.get(item);
        if (state == null) {
            state = new ItemState(actor);
            states.put(item, state);
            state.remember(action, at);
            state.countWrite(action);
            return HistoryWriteDecision.WRITE;
        }
        if (!state.holder.equals(actor)) {
            // Custody moved. Everything remembered for the previous holder is history, not a repeat,
            // and the new holder must not inherit a spammer's exhausted quota.
            state.holder = actor;
            state.reset();
            state.remember(action, at);
            state.countWrite(action);
            return HistoryWriteDecision.WRITE;
        }

        Long previous = state.lastSeen.get(action);
        int written = state.writeCounts.getOrDefault(action, 0);
        state.remember(action, at);
        if (previous == null) {
            state.countWrite(action);
            return HistoryWriteDecision.WRITE;
        }
        if (windowMillis == 0 || at < previous || at - previous > windowMillis) {
            // Outside the window this is new activity — unless this one behaviour has already taken
            // more than its share of the item's history, in which case folding it is what stops a
            // patient attacker from burying a real handover.
            if (written >= maxRowsPerCombination) {
                return HistoryWriteDecision.COALESCE;
            }
            state.countWrite(action);
            return HistoryWriteDecision.WRITE;
        }
        return HistoryWriteDecision.COALESCE;
    }

    /** Number of item identities currently remembered; bounded by the configured capacity. */
    public synchronized int trackedIdentities() {
        return states.size();
    }

    public long windowMillis() {
        return windowMillis;
    }

    private static final class ItemState {
        private UUID holder;
        private final Map<String, Long> lastSeen = new HashMap<>();
        private final Map<String, Integer> writeCounts = new HashMap<>();

        private ItemState(UUID holder) {
            this.holder = Objects.requireNonNull(holder, "holder");
        }

        private void remember(String action, long at) {
            if (lastSeen.size() >= MAX_ACTIONS_PER_ITEM && !lastSeen.containsKey(action)) {
                lastSeen.clear();
                writeCounts.clear();
            }
            lastSeen.put(action, at);
        }

        private void countWrite(String action) {
            writeCounts.merge(action, 1, Integer::sum);
        }

        private void reset() {
            lastSeen.clear();
            writeCounts.clear();
        }
    }
}
