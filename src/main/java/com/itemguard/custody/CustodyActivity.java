package com.itemguard.custody;

import com.itemguard.data.ItemHistory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The activity figures a player actually reads on an item.
 *
 * <p>Raw per-action totals are misleading: throwing an item on the floor and taking it straight back
 * is one continuous possession, yet it adds a drop and a pickup every time, so a player spamming the
 * ground can inflate the visible numbers without the item going anywhere.
 *
 * <p>This collapses those loops. A self drop followed by the same player re-taking it counts as one
 * acquisition, however many times it repeats, and is reported separately as {@code selfCycles} so the
 * raw behaviour is still visible without pretending it is movement.
 */
public final class CustodyActivity {

    private final int handovers;
    private final int meaningfulPickups;
    private final int selfCycles;
    private final int distinctHolders;

    private CustodyActivity(int handovers, int meaningfulPickups, int selfCycles, int distinctHolders) {
        this.handovers = handovers;
        this.meaningfulPickups = meaningfulPickups;
        this.selfCycles = selfCycles;
        this.distinctHolders = distinctHolders;
    }

    public static CustodyActivity of(List<ItemHistory> newestFirst, CustodyTransferPolicy policy) {
        Objects.requireNonNull(newestFirst, "newestFirst");
        Objects.requireNonNull(policy, "policy");
        CustodyChain chain = CustodyChain.replay(newestFirst, policy);

        List<ItemHistory> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);

        int acquisitions = 0;
        int selfCycles = 0;
        UUID holder = null;
        for (ItemHistory row : oldestFirst) {
            if (row == null || row.getPlayerUuid() == null) {
                continue;
            }
            if (!CustodyActionPolicy.claimsCustody(row.getAction())) {
                continue;
            }
            UUID actor = row.getPlayerUuid();
            if (actor.equals(holder)) {
                // The same player taking their own item back: not a new acquisition.
                selfCycles++;
                continue;
            }
            acquisitions++;
            holder = actor;
        }
        return new CustodyActivity(chain.transfers(), acquisitions, selfCycles, chain.distinctHolders());
    }

    /** Genuine handovers between different players, throttled against pair farming. */
    public int handovers() {
        return handovers;
    }

    /** Acquisitions that changed who was holding the item; self drop/re-pick loops are collapsed. */
    public int meaningfulPickups() {
        return meaningfulPickups;
    }

    /** How many times a holder dropped and immediately took back their own item. */
    public int selfCycles() {
        return selfCycles;
    }

    public int distinctHolders() {
        return distinctHolders;
    }
}
