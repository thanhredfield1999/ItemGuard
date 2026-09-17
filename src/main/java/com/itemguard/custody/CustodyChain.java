package com.itemguard.custody;

import com.itemguard.data.ItemHistory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A custody chain derived from recorded history rows.
 *
 * <p>Derived, not stored: the chain is replayed from the same history the plugin already writes, so
 * it needs no schema change and can never claim more than the database actually recorded. Because
 * history queries are windowed, {@link #rowsConsidered()} states how much was examined; the chain
 * describes that window, not the item's whole life.
 */
public final class CustodyChain {

    private final CustodyState state;
    private final Map<UUID, String> names;
    private final int rowsConsidered;

    private CustodyChain(CustodyState state, Map<UUID, String> names, int rowsConsidered) {
        this.state = state;
        this.names = Map.copyOf(names);
        this.rowsConsidered = rowsConsidered;
    }

    /**
     * Replays history into a chain.
     *
     * @param newestFirst history rows as the repository returns them, newest first
     * @param policy      the counting rules
     */
    public static CustodyChain replay(List<ItemHistory> newestFirst, CustodyTransferPolicy policy) {
        Objects.requireNonNull(newestFirst, "newestFirst");
        Objects.requireNonNull(policy, "policy");
        List<ItemHistory> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);
        CustodyState state = CustodyState.empty();
        Map<UUID, String> names = new LinkedHashMap<>();
        for (ItemHistory row : oldestFirst) {
            if (row == null || row.getPlayerUuid() == null) {
                continue;
            }
            if (!CustodyActionPolicy.claimsCustody(row.getAction())) {
                continue;
            }
            state = policy.observe(state, row.getPlayerUuid(), row.getTimestamp()).state();
            if (row.getPlayerName() != null && !row.getPlayerName().isBlank()) {
                names.put(row.getPlayerUuid(), row.getPlayerName());
            }
        }
        return new CustodyChain(state, names, newestFirst.size());
    }

    /** Genuine handovers inside the replayed window. Self drop/pickup and ping-pong are excluded. */
    public int transfers() {
        return state.transfers();
    }

    /** How many different players are recorded as having held it. Not farmable by two accounts. */
    public int distinctHolders() {
        return state.distinctHolders();
    }

    /** The last recorded holder. This is recorded history, not proof of present possession. */
    public UUID currentHolder() {
        return state.holder();
    }

    /** Distinct holders in the order they first took the item. */
    public List<UUID> holders() {
        return state.knownHolders();
    }

    /** Display names for the holders, in the same order; staff-only presentation decides visibility. */
    public List<String> holderNames() {
        List<String> ordered = new ArrayList<>();
        for (UUID holder : state.knownHolders()) {
            ordered.add(names.getOrDefault(holder, "unknown"));
        }
        return List.copyOf(ordered);
    }

    /** Number of history rows examined, so callers can state the window rather than imply totals. */
    public int rowsConsidered() {
        return rowsConsidered;
    }
}
