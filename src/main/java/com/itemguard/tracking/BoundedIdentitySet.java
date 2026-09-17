package com.itemguard.tracking;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A membership set that forgets its coldest entries instead of growing forever.
 *
 * <p>{@link IdentityReadinessCoordinator} keeps one entry per unique item it has reconciled.
 * Nothing removed entries, so a server with a long uptime and a steady flow of new gear leaked
 * memory slowly and invisibly — no unit test and no short runtime fixture can surface that.
 *
 * <p>Eviction is safe here, which is what makes this the right fix rather than a risky one:
 * readiness is derived from tags stored on the item itself, so a forgotten entry is simply
 * recomputed the next time that item is touched. The cost of evicting too eagerly is one extra
 * reconciliation; the cost of never evicting is an out-of-memory error after weeks.
 *
 * <p>Access order, not insertion order — an item being actively moved keeps its entry while
 * items nobody has touched in months are the ones dropped.
 *
 * <p>The map is held directly rather than wrapped with {@code Collections.newSetFromMap}: that
 * wrapper implements {@code contains} with {@code containsKey}, and {@code containsKey} does
 * <em>not</em> count as an access for {@code LinkedHashMap}'s access ordering. Using it made
 * hot entries evictable and cold ones survive — the exact opposite of the intent, and caught
 * only because the test checked that an actively used entry survives rather than just checking
 * the size cap.
 */
public final class BoundedIdentitySet {

    private final LinkedHashMap<String, Boolean> entries;

    public BoundedIdentitySet(int maximumEntries) {
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > maximumEntries;
            }
        };
    }

    public synchronized boolean add(String key) {
        return entries.put(key, Boolean.TRUE) == null;
    }

    /** Uses {@code get}, so a lookup marks the entry as recently used. */
    public synchronized boolean contains(String key) {
        return entries.get(key) != null;
    }

    public synchronized boolean remove(String key) {
        return entries.remove(key) != null;
    }

    public synchronized int size() {
        return entries.size();
    }
}
