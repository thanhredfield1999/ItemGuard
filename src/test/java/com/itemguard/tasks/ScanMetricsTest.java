package com.itemguard.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The product requirements ask for scan metrics (p50/p95 and queue state), which nothing measured:
 * an owner could see TPS and nothing else, so "is the scan keeping up" had no answer.
 *
 * <p>These are the behaviours a reader depends on: an empty window reports zero rather than NaN, the
 * percentiles describe the samples actually kept, the sample window stays bounded however long the
 * server runs, and the counters can be written from the database thread that finalizes an epoch while
 * a command reads them on the server thread.
 */
class ScanMetricsTest {

    @Test
    void anEmptyWindowReportsZeroInsteadOfNaN() {
        ScanMetrics metrics = new ScanMetrics();

        assertEquals(0, metrics.sampleCount());
        assertEquals(0.0, metrics.averageScanMillis());
        assertEquals(0.0, metrics.percentileScanMillis(50));
        assertEquals(0.0, metrics.lastScanMillis());
    }

    @Test
    void percentilesDescribeTheSamplesThatWereKept() {
        ScanMetrics metrics = new ScanMetrics();
        for (int millis = 1; millis <= 100; millis++) {
            metrics.recordScan(TimeUnit.MILLISECONDS.toNanos(millis));
        }

        assertEquals(100, metrics.sampleCount());
        assertTrue(metrics.percentileScanMillis(50) >= 50 && metrics.percentileScanMillis(50) <= 51,
            "p50 of 1..100 ms is 50-51, was " + metrics.percentileScanMillis(50));
        assertTrue(metrics.percentileScanMillis(95) >= 95 && metrics.percentileScanMillis(95) <= 96,
            "p95 of 1..100 ms is 95-96, was " + metrics.percentileScanMillis(95));
        assertEquals(100.0, metrics.lastScanMillis());
    }

    @Test
    void theSampleWindowStaysBounded() {
        ScanMetrics metrics = new ScanMetrics();
        IntStream.range(0, 5_000).forEach(i -> metrics.recordScan(1_000_000L));

        assertTrue(metrics.sampleCount() <= ScanMetrics.SAMPLE_WINDOW,
            "a long-running server must not grow the sample array without bound");
        assertEquals(5_000, metrics.scanCount(), "counters are totals, not windowed");
    }

    @Test
    void countersAndTheInFlightFlagAreVisibleToAReaderOnAnotherThread() throws Exception {
        ScanMetrics metrics = new ScanMetrics();
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 1_000; i++) {
                        metrics.recordScan(500_000L);
                        metrics.recordEpoch();
                        metrics.recordFinding();
                        metrics.recordAlert(1);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "workers did not finish");

        assertEquals(4_000, metrics.scanCount());
        assertEquals(4_000, metrics.epochCount());
        assertEquals(4_000, metrics.findingCount());
        assertEquals(4_000, metrics.alertCount());
        assertTrue(metrics.sampleCount() <= ScanMetrics.SAMPLE_WINDOW);

        metrics.setSweepInFlight(true);
        assertTrue(metrics.sweepInFlight());
        metrics.recordSweepPass(TimeUnit.SECONDS.toNanos(2));
        assertTrue(metrics.lastSweepMillis() >= 1_999);
    }
}
