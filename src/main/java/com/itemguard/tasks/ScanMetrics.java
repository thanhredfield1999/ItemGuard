package com.itemguard.tasks;

/**
 * Bounded, thread-safe counters and latency samples for the observation scan.
 *
 * <p>The product requirements ask an operator to be able to tell whether the scan is keeping up
 * (p50/p95 and queue state), and nothing measured it: the only readable number was server TPS, which
 * says nothing about this plugin's own work. This class is deliberately small and allocation-free
 * after construction — it is written on the scan path, which is the code the measurement exists to
 * watch.
 *
 * <p>Writers and readers are different threads: the scan runs on the server thread, findings arrive
 * from the database thread that finalizes an epoch, and a command reads the numbers. Every method is
 * synchronized on this instance; contention is a few writes per scan cycle, so a lock is cheaper than
 * the coordination an array of atomics would need.
 *
 * <p>The sample window is fixed: percentiles describe the most recent {@link #SAMPLE_WINDOW} scans,
 * and the totals are unbounded counters. A window that grows forever would turn a diagnostic into a
 * leak.
 */
public final class ScanMetrics {

    /** How many scan durations are kept for the percentile window. */
    public static final int SAMPLE_WINDOW = 256;

    private final long[] samplesNanos = new long[SAMPLE_WINDOW];
    private int sampleCount;
    private int sampleCursor;

    private long scanCount;
    private long epochCount;
    private long findingCount;
    private long alertCount;
    private long sweepPassCount;
    private long lastScanNanos;
    private long lastSweepNanos;
    private long skippedBusyScans;
    private boolean sweepInFlight;

    /** One scan tick completed, taking the given wall time. */
    public synchronized void recordScan(long durationNanos) {
        long bounded = Math.max(0L, durationNanos);
        samplesNanos[sampleCursor] = bounded;
        sampleCursor = (sampleCursor + 1) % SAMPLE_WINDOW;
        if (sampleCount < SAMPLE_WINDOW) {
            sampleCount++;
        }
        scanCount++;
        lastScanNanos = bounded;
    }

    /** A scan tick was skipped because the previous chunk sweep had not finished. */
    public synchronized void recordSkippedBusyScan() {
        skippedBusyScans++;
    }

    /** A chunk-container sweep pass finished, taking the given wall time. */
    public synchronized void recordSweepPass(long durationNanos) {
        sweepPassCount++;
        lastSweepNanos = Math.max(0L, durationNanos);
        sweepInFlight = false;
    }

    /** An observation epoch was completed (and audited). */
    public synchronized void recordEpoch() {
        epochCount++;
    }

    /** A duplicate finding was produced. */
    public synchronized void recordFinding() {
        findingCount++;
    }

    /** An alert was delivered to the given number of recipients. */
    public synchronized void recordAlert(int recipients) {
        if (recipients > 0) {
            alertCount++;
        }
    }

    /** Whether a chunk-container sweep is currently in flight (the scan's backlog state). */
    public synchronized void setSweepInFlight(boolean inFlight) {
        sweepInFlight = inFlight;
    }

    public synchronized boolean sweepInFlight() {
        return sweepInFlight;
    }

    public synchronized long scanCount() {
        return scanCount;
    }

    public synchronized long epochCount() {
        return epochCount;
    }

    public synchronized long findingCount() {
        return findingCount;
    }

    public synchronized long alertCount() {
        return alertCount;
    }

    public synchronized long sweepPassCount() {
        return sweepPassCount;
    }

    public synchronized long skippedBusyScans() {
        return skippedBusyScans;
    }

    public synchronized int sampleCount() {
        return sampleCount;
    }

    public synchronized double lastScanMillis() {
        return nanosToMillis(lastScanNanos);
    }

    public synchronized double lastSweepMillis() {
        return nanosToMillis(lastSweepNanos);
    }

    /** Mean of the kept window, or 0 when nothing has been sampled yet. */
    public synchronized double averageScanMillis() {
        if (sampleCount == 0) {
            return 0.0;
        }
        long total = 0;
        for (int i = 0; i < sampleCount; i++) {
            total += samplesNanos[i];
        }
        return nanosToMillis(total / sampleCount);
    }

    /**
     * The given percentile of the kept window, or 0 when nothing has been sampled.
     *
     * <p>Nearest-rank on a sorted copy: with a window this small, correctness of the reported number
     * matters more than avoiding a 256-element copy on a diagnostic call.
     */
    public synchronized double percentileScanMillis(int percentile) {
        if (sampleCount == 0) {
            return 0.0;
        }
        int bounded = Math.max(0, Math.min(100, percentile));
        long[] sorted = new long[sampleCount];
        System.arraycopy(samplesNanos, 0, sorted, 0, sampleCount);
        java.util.Arrays.sort(sorted);
        int rank = (int) Math.ceil(bounded / 100.0 * sampleCount) - 1;
        if (rank < 0) {
            rank = 0;
        }
        if (rank >= sampleCount) {
            rank = sampleCount - 1;
        }
        return nanosToMillis(sorted[rank]);
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
