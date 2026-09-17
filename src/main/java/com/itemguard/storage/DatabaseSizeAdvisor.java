package com.itemguard.storage;

import java.util.Optional;

/**
 * Watches how large the ItemGuard database has grown and says something before it becomes a
 * problem — without ever deleting anything.
 *
 * <p>LITE keeps history forever on purpose ({@code ConfigManager.getCleanupIntervalHours()}
 * returns 0 for this edition). That is the right default for an investigation tool: a log with
 * holes in it cannot settle an argument. But four tables have no pruning path at all, and
 * {@code tag_publications} stores a binary snapshot per tag event, so a busy server accumulates
 * real disk usage over months.
 *
 * <p>Finding that out from a full disk is the worst way to learn it. So this advisor reports,
 * which is the same contract as everything else in LITE: it never removes data, it tells the
 * admin what is happening and lets them decide.
 *
 * <p>Not thread-safe by design — it is called from the single scheduled task that owns it.
 */
public final class DatabaseSizeAdvisor {

    private static final long MB = 1024L * 1024L;

    private final long thresholdBytes;

    /** Size at which the last warning fired; the next one waits until double this. */
    private long lastWarnedAt;

    /**
     * @param thresholdBytes size at which to start warning, or 0 to disable warnings entirely
     *                       so an admin who has accepted the trade-off is not nagged.
     */
    public DatabaseSizeAdvisor(long thresholdBytes) {
        this.thresholdBytes = thresholdBytes;
    }

    /**
     * Returns a message to log, or empty if nothing needs saying right now.
     *
     * <p>After warning once, it stays quiet until the file has doubled. A message on every
     * scheduled check would be noise, and noisy plugins get uninstalled before they get read.
     */
    public Optional<String> review(long currentBytes) {
        if (thresholdBytes <= 0) {
            return Optional.empty();
        }
        if (currentBytes < thresholdBytes) {
            return Optional.empty();
        }
        if (lastWarnedAt > 0 && currentBytes < lastWarnedAt * 2) {
            return Optional.empty();
        }
        lastWarnedAt = currentBytes;
        return Optional.of(
            "itemguard.db has reached " + readable(currentBytes) + ". LITE never deletes history,"
                + " so this file only grows. Nothing is wrong — but if the size becomes a problem,"
                + " stop the server and archive or delete the file; a fresh one is created on the"
                + " next start, and items keep their IDs."
        );
    }

    /**
     * Formats a byte count the way an admin reads it. Raw bytes make the reader do arithmetic
     * at exactly the moment they are trying to decide whether to care.
     */
    public static String readable(long bytes) {
        if (bytes < 1024L * MB) {
            return (bytes / MB) + " MB";
        }
        double gb = bytes / (double) (1024L * MB);
        return String.format(java.util.Locale.ROOT, "%.1f GB", gb);
    }
}
