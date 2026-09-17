package com.itemguard.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LITE never deletes history — {@code getCleanupIntervalHours()} returns 0 for this edition on
 * purpose, because an investigation log you cannot trust to be complete is worth very little.
 *
 * <p>The cost of that decision is that the database only ever grows, and four tables have no
 * pruning path at all ({@code tracked_items}, {@code tag_publications}, {@code item_snapshots},
 * {@code duplicate_findings}). {@code tag_publications} is the heaviest because it stores a
 * binary snapshot per tag event.
 *
 * <p>An admin discovering that for themselves after six months is the worst possible way to
 * learn it. So the plugin watches its own file and says something once it crosses a threshold.
 * It still does not delete anything: it reports, which is the same contract as the rest of LITE.
 */
class DatabaseSizeAdvisorTest {

    private static final long MB = 1024L * 1024L;

    @Test
    @DisplayName("stays quiet while the database is small")
    void quietBelowThreshold() {
        DatabaseSizeAdvisor advisor = new DatabaseSizeAdvisor(500 * MB);
        assertFalse(advisor.review(10 * MB).isPresent());
        assertFalse(advisor.review(499 * MB).isPresent());
    }

    @Test
    @DisplayName("warns once the database crosses the threshold")
    void warnsAtThreshold() {
        DatabaseSizeAdvisor advisor = new DatabaseSizeAdvisor(500 * MB);
        Optional<String> warning = advisor.review(500 * MB);
        assertTrue(warning.isPresent());
        // The message has to be actionable: the actual size, and what the admin can do.
        assertTrue(warning.get().contains("500"), warning.get());
        assertTrue(warning.get().toLowerCase().contains("itemguard.db"), warning.get());
    }

    @Test
    @DisplayName("does not repeat the same warning every check")
    void warnsOnceUntilItGrowsSubstantially() {
        // A message every 30 seconds is spam, and spam gets the plugin uninstalled. Warn, then
        // stay silent until the file has grown meaningfully again.
        DatabaseSizeAdvisor advisor = new DatabaseSizeAdvisor(500 * MB);
        assertTrue(advisor.review(500 * MB).isPresent());
        assertFalse(advisor.review(505 * MB).isPresent(), "repeated the warning too eagerly");
        assertFalse(advisor.review(600 * MB).isPresent(), "repeated the warning too eagerly");
        assertTrue(advisor.review(1000 * MB).isPresent(), "silent after doubling in size");
    }

    @Test
    @DisplayName("reports size in units an admin reads, not raw bytes")
    void humanReadableSize() {
        DatabaseSizeAdvisor advisor = new DatabaseSizeAdvisor(500 * MB);
        String warning = advisor.review(1536 * MB).orElseThrow();
        // 1536 MB is 1.5 GB. Printing "1610612736 bytes" makes the admin do the conversion.
        assertTrue(warning.contains("1.5 GB"), warning);
    }

    @Test
    @DisplayName("a threshold of zero disables the advisor entirely")
    void thresholdZeroDisables() {
        // Admins who know the trade-off should be able to silence it without patching the jar.
        DatabaseSizeAdvisor advisor = new DatabaseSizeAdvisor(0);
        assertFalse(advisor.review(50_000 * MB).isPresent());
    }

    @Test
    @DisplayName("formats sizes at the boundary between units")
    void formatsBoundaries() {
        assertEquals("512 MB", DatabaseSizeAdvisor.readable(512 * MB));
        assertEquals("1.0 GB", DatabaseSizeAdvisor.readable(1024 * MB));
        assertEquals("2.5 GB", DatabaseSizeAdvisor.readable(2560 * MB));
    }
}
