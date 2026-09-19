package com.itemguard.persistence;

import com.itemguard.data.ItemData;
import com.itemguard.dupe.DuplicateAction;
import com.itemguard.dupe.HolderType;
import com.itemguard.dupe.ItemObservation;
import com.itemguard.dupe.ObservationKey;
import com.itemguard.snapshot.ItemSnapshotCodec;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for C1 (review 2026-09-16): a scan epoch spans real time, so two locations inside one
 * epoch do not by themselves prove that two copies exist.
 *
 * <p>The player-inventory scan happens in a single tick, but the container sweep is spread over
 * many ticks and writes into the same epoch. At 8 chunks per tick that window runs from about 12
 * seconds on a small server to over a minute on a loaded one. An item a player carries and then
 * stores in a chest is seen twice — in a player slot at tick T and in the container later in the
 * same epoch — and the audit called that a confirmed duplicate.
 *
 * <p>Thanh's decision (2026-09-16): a confirmation now requires the same identity at two or more
 * locations in <em>two consecutive epochs</em>. An item that was actually moved is in one place by
 * the next epoch, so the second sighting never arrives. Two real copies stay put and are confirmed
 * one cycle later. Cross-source detection (one copy in a hand, one in a chest) is preserved.
 */
class DuplicateConfirmationEpochRuleTest {

    @TempDir
    Path tempDir;

    @Test
    void aMoveBetweenPlayerAndContainerIsNotAConfirmation() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("move.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            UUID itemUuid = UUID.randomUUID();
            trackIdentity(repository, "MOVE01", itemUuid);

            long epoch = 5_000L;
            // Tick T: the item is in the player's third slot.
            observe(repository, "MOVE01", itemUuid, HolderType.PLAYER, "player-1", 3, epoch, epoch);
            // Tick T+40s: the sweep reaches the chest the player stored it in — same epoch.
            observe(repository, "MOVE01", itemUuid, HolderType.CONTAINER, "BLOCK:world:1:2:3", 5,
                epoch, epoch + 40_000L);

            assertEquals(
                0,
                audit(repository, epoch).toCompletableFuture().get(2, TimeUnit.SECONDS).size(),
                "an item carried and then stored is a move, not a duplicate"
            );
        }
    }

    @Test
    void twoLocationsInTwoConsecutiveEpochsAreConfirmed() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("confirmed.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            UUID itemUuid = UUID.randomUUID();
            trackIdentity(repository, "DUPE01", itemUuid);

            long firstEpoch = 5_000L;
            observe(repository, "DUPE01", itemUuid, HolderType.PLAYER, "player-1", 3, firstEpoch,
                firstEpoch);
            observe(repository, "DUPE01", itemUuid, HolderType.CONTAINER, "BLOCK:world:1:2:3", 5,
                firstEpoch, firstEpoch + 40_000L);
            assertEquals(
                0,
                audit(repository, firstEpoch).toCompletableFuture().get(2, TimeUnit.SECONDS).size(),
                "one epoch alone is a sighting, not a confirmation"
            );

            long secondEpoch = 900_000L;
            observe(repository, "DUPE01", itemUuid, HolderType.PLAYER, "player-1", 3, secondEpoch,
                secondEpoch);
            observe(repository, "DUPE01", itemUuid, HolderType.CONTAINER, "BLOCK:world:1:2:3", 5,
                secondEpoch, secondEpoch + 40_000L);

            assertEquals(
                1,
                audit(repository, secondEpoch).toCompletableFuture().get(2, TimeUnit.SECONDS).size(),
                "two copies sitting still are seen at two locations again, so this one is confirmed"
            );
            assertEquals(1, repository.countDuplicateFindings(itemUuid, secondEpoch));
        }
    }

    /**
     * H5 (review 2026-09-17): the suppression window was wall-clock only, and a window is only
     * meaningful relative to the real gap between two audits — a sweep that overruns a cycle, or a
     * server under 20 TPS, makes that gap larger than the configured window, and the setting becomes
     * inert again. The audit now also refuses to re-report an identity in two consecutive epochs,
     * whatever their distance in real time.
     */
    @Test
    void aRepeatInTheVeryNextAuditIsSuppressedEvenWithATinyWindow() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("repeat.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            UUID itemUuid = UUID.randomUUID();
            trackIdentity(repository, "RPT001", itemUuid);

            long first = 10_000L;
            observe(repository, "RPT001", itemUuid, HolderType.PLAYER, "player-1", 3, first, first);
            observe(repository, "RPT001", itemUuid, HolderType.CONTAINER, "BLOCK:world:1:2:3", 5,
                first, first);
            assertEquals(0, audit(repository, first, 1L).toCompletableFuture()
                .get(2, TimeUnit.SECONDS).size(), "one epoch is a sighting, not a confirmation");

            long second = 20_000L;
            observe(repository, "RPT001", itemUuid, HolderType.PLAYER, "player-1", 3, second, second);
            observe(repository, "RPT001", itemUuid, HolderType.CONTAINER, "BLOCK:world:1:2:3", 5,
                second, second);
            assertEquals(1, audit(repository, second, 1L).toCompletableFuture()
                .get(2, TimeUnit.SECONDS).size(), "two copies are confirmed once");

            // The very next audit sees them again. A 1 ms window suppresses nothing by time; the
            // epoch clause is what has to stop the repeat.
            long third = 30_000L;
            observe(repository, "RPT001", itemUuid, HolderType.PLAYER, "player-1", 3, third, third);
            observe(repository, "RPT001", itemUuid, HolderType.CONTAINER, "BLOCK:world:1:2:3", 5,
                third, third);
            assertEquals(0, audit(repository, third, 1L).toCompletableFuture()
                .get(2, TimeUnit.SECONDS).size(),
                "the immediately following audit must not re-alert the same identity");
        }
    }

    /**
     * The other half of the rule: the epoch clause suppresses one audit, not every audit after it. A
     * duplicate that is still there an audit later is reported again, which is what the statistic and
     * the staff alert are for.
     */
    @Test
    void aRepeatTwoAuditsLaterIsReportedAgain() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("gap.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            UUID itemUuid = UUID.randomUUID();
            trackIdentity(repository, "GAP001", itemUuid);

            long[] epochs = {1_000L, 2_000L, 3_000L, 4_000L};
            int[] expected = {0, 1, 0, 1};
            for (int index = 0; index < epochs.length; index++) {
                long epoch = epochs[index];
                observe(repository, "GAP001", itemUuid, HolderType.PLAYER, "player-1", 3, epoch, epoch);
                observe(repository, "GAP001", itemUuid, HolderType.CONTAINER, "BLOCK:world:1:2:3", 5,
                    epoch, epoch);
                assertEquals(expected[index],
                    audit(repository, epoch, 1L).toCompletableFuture().get(2, TimeUnit.SECONDS).size(),
                    "audit at epoch " + epoch + " (first sighting, first alert, suppressed repeat, "
                        + "alert again once the window and the epoch have both moved on)");
            }
        }
    }

    private java.util.concurrent.CompletableFuture<java.util.List<com.itemguard.dupe.DuplicateFinding>>
        audit(ItemSqliteRepository repository, long scanEpoch) {
        return audit(repository, scanEpoch, 5_000L);
    }

    private java.util.concurrent.CompletableFuture<java.util.List<com.itemguard.dupe.DuplicateFinding>>
        audit(ItemSqliteRepository repository, long scanEpoch, long cooldownMillis) {
        return repository.completeObservationEpochAndAudit(
            scanEpoch,
            true,
            DuplicateAction.NOTIFY,
            cooldownMillis,
            scanEpoch
        );
    }

    private void trackIdentity(ItemSqliteRepository repository, String code, UUID itemUuid) {
        ItemData item = new ItemData(code, itemUuid);
        item.setMaterial(Material.DIAMOND_SWORD);
        item.setCreatedAt(1_000L);
        item.setLastSeenAt(1_000L);
        repository.saveItemWithSnapshot(
            item,
            new ItemSnapshotCodec(1024).capture(new byte[]{1, 2, 3}),
            1_000L
        );
    }

    private void observe(
        ItemSqliteRepository repository,
        String code,
        UUID itemUuid,
        HolderType holderType,
        String holderId,
        int slot,
        long scanEpoch,
        long observedAt
    ) {
        // The epoch is the subject of these tests; the timestamp is fixture noise. Under the
        // retention window a timestamp from before 2001 is expired as soon as an audit completes, so
        // an epoch-shaped value is stamped with the current time instead. Realistic values pass
        // through untouched.
        long effectiveObservedAt = observedAt < 1_000_000_000_000L
            ? System.currentTimeMillis()
            : observedAt;
        repository.recordObservation(new ItemObservation(
            itemUuid,
            code,
            scanEpoch,
            new ObservationKey(holderType, holderId, slot),
            effectiveObservedAt
        ));
    }
}
