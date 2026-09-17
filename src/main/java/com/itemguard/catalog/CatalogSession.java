package com.itemguard.catalog;

/** Main-thread generation guard, independent from Bukkit objects. */
public final class CatalogSession {
    private long generation;
    private boolean closed;
    public long begin() {
        if (closed) throw new IllegalStateException("Closed catalog session");
        return ++generation;
    }
    public boolean accepts(long candidate) { return !closed && candidate == generation; }
    public void close() { closed = true; }
}
