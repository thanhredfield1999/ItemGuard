package com.itemguard.persistence;

import com.itemguard.dupe.HolderType;
import com.itemguard.dupe.ItemObservation;
import com.itemguard.dupe.ObservationKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observations are kept for a window, not "until my next audit".
 *
 * <p>The rule this replaces deleted every row whose `scan_epoch` was older than the completing
 * server's current epoch, on a table that M3 had just given a `server_id` column and an
 * `(item_uuid, server_id, observed_at)` index for the question "which servers has this identity been
 * seen on inside a window". On a shared database each server therefore erased the rows the others had
 * written seconds earlier: the two-Paper gate could never observe a cross-server sighting and a
 * migrated target lost its observations after the first audit. Both were caught by running the gates,
 * after the offline suite had passed for months.
 *
 * <p>The window is time-based here rather than server-scoped, and that is deliberate: the rows that
 * must survive are the ones inside the window, whoever wrote them, and a row outside every server's
 * window is exactly what retention is for.
 */
class ObservationRetentionTest {

    @TempDir
    Path tempDir;

    private static final long NOW = 1_800_000_000_000L;

    private static ItemObservation observation(long epoch, String holder, int slot, long observedAt) {
        return new ItemObservation(
            ITEM_UUID,
            "AB12CD",
            epoch,
            new ObservationKey(HolderType.PLAYER, holder, slot),
            observedAt
        );
    }

    private static final java.util.UUID ITEM_UUID =
        java.util.UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    @Test
    void anAuditExpiresOnlyRowsOlderThanTheWindow() {
        List<Throwable> failures = new ArrayList<>();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("retention.db"), failures::add
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            // Written by "another server" a moment ago: inside the window, so it must survive even
            // though its epoch is older than the epoch this audit is completing.
            repository.recordObservation(observation(4_000L, "other-server-holder", 9, NOW - 1_000L));
            // Outside the window: this is what retention is for.
            repository.recordObservation(observation(3_000L, "ancient-holder", 10, NOW - 3_600_000L));

            repository.completeObservationEpoch(9_000L, NOW - 60_000L);
            owner.flush();

            assertEquals(
                1,
                repository.countObservations(
                    java.util.UUID.fromString("00000000-0000-0000-0000-0000000000ab"), 4_000L
                ),
                "a fresh row from another epoch must survive: an epoch is per server, and deleting "
                    + "across servers is what made cross-server sightings impossible"
            );
            assertEquals(
                0,
                repository.countObservations(
                    java.util.UUID.fromString("00000000-0000-0000-0000-0000000000ab"), 3_000L
                ),
                "a row older than the window must be gone"
            );
            assertTrue(failures.isEmpty(), "the retention delete must not be a database failure: " + failures);
        }
    }

    @Test
    void theShippedDefaultWindowKeepsARecentRow() {
        List<Throwable> failures = new ArrayList<>();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("default.db"), failures::add
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            long justNow = System.currentTimeMillis() - 5_000L;
            repository.recordObservation(observation(1L, "holder", 0, justNow));

            repository.completeObservationEpoch(2L);
            owner.flush();

            assertEquals(
                1,
                repository.countObservations(
                    java.util.UUID.fromString("00000000-0000-0000-0000-0000000000ab"), 1L
                ),
                "the no-argument overload must keep rows inside the shipped 30 minute window"
            );
            assertTrue(failures.isEmpty(), failures.toString());
        }
    }
}
