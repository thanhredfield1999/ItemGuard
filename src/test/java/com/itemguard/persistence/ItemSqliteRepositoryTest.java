package com.itemguard.persistence;

import com.itemguard.data.ItemData;
import com.itemguard.data.ItemHistory;
import com.itemguard.dupe.DuplicateAction;
import com.itemguard.dupe.DuplicateFinding;
import com.itemguard.dupe.DuplicateStatus;
import com.itemguard.dupe.HolderType;
import com.itemguard.dupe.ItemObservation;
import com.itemguard.dupe.ObservationKey;
import com.itemguard.snapshot.ItemSnapshot;
import com.itemguard.snapshot.ItemSnapshotCodec;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemSqliteRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void saveReadAndUpdatePreserveCanonicalIdentity() {
        List<Throwable> failures = new ArrayList<>();
        UUID itemUuid = UUID.randomUUID();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("items.db"), failures::add
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            ItemData item = item("AB12CD", itemUuid, "Thanh", Material.DIAMOND_SWORD);

            repository.saveItem(item);
            repository.updateLastAction(
                "AB12CD", "PICKUP", "world (1, 2, 3)", "Thanh", item.getOwnerUuid(), 2_000L
            );
            owner.flush();

            ItemData loaded = repository.getItem("AB12CD").orElseThrow();
            assertEquals(itemUuid, loaded.getItemUuid());
            assertEquals("PICKUP", loaded.getLastAction());
            assertEquals("world (1, 2, 3)", loaded.getLastLocation());
            assertEquals(1, loaded.getDetectionCount());
            assertEquals(itemUuid, repository.getItemByUuid(itemUuid).orElseThrow().getItemUuid());
            assertTrue(failures.isEmpty());
        }
    }

    @Test
    void codeCannotBeReboundToDifferentUuid() {
        List<Throwable> failures = new ArrayList<>();
        UUID originalUuid = UUID.randomUUID();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("rebind.db"), failures::add
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(item("AB12CD", originalUuid, "Thanh", Material.DIAMOND_SWORD));
            repository.saveItem(item("AB12CD", UUID.randomUUID(), "Other", Material.NETHERITE_SWORD));
            owner.flush();

            ItemData loaded = repository.getItem("AB12CD").orElseThrow();
            assertEquals(originalUuid, loaded.getItemUuid());
            assertEquals("Thanh", loaded.getOwnerName());
            assertFalse(failures.isEmpty());
        }
    }

    @Test
    void uuidCannotBeReboundToDifferentCode() {
        List<Throwable> failures = new ArrayList<>();
        UUID itemUuid = UUID.randomUUID();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("uuid-rebind.db"), failures::add
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(item("AB12CD", itemUuid, "Thanh", Material.DIAMOND_SWORD));
            repository.saveItem(item("EF34GH", itemUuid, "Other", Material.NETHERITE_SWORD));
            owner.flush();

            assertEquals("AB12CD", repository.getItemByUuid(itemUuid).orElseThrow().getCode());
            assertTrue(repository.getItem("EF34GH").isEmpty());
            assertFalse(failures.isEmpty());
        }
    }

    @Test
    void historySearchCleanupAndRestartUsePersistedData() {
        Path database = tempDir.resolve("history.db");
        UUID itemUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(database)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            ItemData item = item("AB12CD", itemUuid, "Thanh", Material.DIAMOND_SWORD);
            item.setOwnerUuid(ownerUuid);
            repository.saveItem(item);

            ItemHistory old = history("AB12CD", itemUuid, ownerUuid, 100L);
            ItemHistory recent = history("AB12CD", itemUuid, ownerUuid, 10_000L);
            repository.logHistory(old);
            repository.logHistory(recent);
            owner.flush();

            assertEquals(2, repository.getHistoryCount("AB12CD"));
            assertEquals(1, repository.searchItems("Thanh").size());
            assertEquals(1, repository.getItemsByPlayer(ownerUuid).size());
            assertEquals(1, repository.deleteHistoryBefore(1_000L));
        }

        try (SqliteConnectionOwner reopened = new SqliteConnectionOwner(database)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(reopened);
            assertEquals(1, repository.getHistory("AB12CD", 20).size());
            assertEquals(10_000L, repository.getHistory("AB12CD", 20).getFirst().getTimestamp());
        }
    }

    @Test
    void observationLedgerIsIdempotentAndRequiresCompletedEpoch() {
        UUID itemUuid = UUID.randomUUID();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("observations.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            ItemObservation slotFive = new ItemObservation(
                itemUuid,
                "AB12CD",
                77L,
                new ObservationKey(HolderType.PLAYER, "player-a", 5),
                1_000L
            );
            ItemObservation slotEight = new ItemObservation(
                itemUuid,
                "AB12CD",
                77L,
                new ObservationKey(HolderType.PLAYER, "player-a", 8),
                1_001L
            );

            repository.recordObservation(slotFive);
            repository.recordObservation(slotFive);
            repository.recordObservation(slotEight);
            owner.flush();

            assertEquals(
                DuplicateStatus.SUSPECTED,
                repository.assessDuplicate(itemUuid, 77L).status()
            );
            assertEquals(2, repository.countObservations(itemUuid, 77L));

            repository.completeObservationEpoch(77L);
            owner.flush();

            assertEquals(
                DuplicateStatus.CONFIRMED,
                repository.assessDuplicate(itemUuid, 77L).status()
            );
        }
    }

    @Test
    void completedEpochAuditsConfirmedDuplicateExactlyOnce() {
        UUID itemUuid = UUID.randomUUID();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("duplicate-finding.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(item("AB12CD", itemUuid, "Thanh", Material.DIAMOND_SWORD));
            // A confirmation requires two consecutive epochs (C1, 2026-09-16). Seed the epoch
            // before, which by definition on its own cannot confirm anything.
            recordTwoLocations(repository, itemUuid, 87L);
            assertTrue(repository.completeObservationEpochAndAudit(
                87L, true, DuplicateAction.NOTIFY, 5_000L, 1_500L
            ).join().isEmpty());
            repository.recordObservation(observation(
                itemUuid, "AB12CD", 88L, "player-a", 5, 1_000L
            ));
            repository.recordObservation(observation(
                itemUuid, "AB12CD", 88L, "player-a", 8, 1_001L
            ));

            List<DuplicateFinding> first = repository.completeObservationEpochAndAudit(
                88L, true, DuplicateAction.NOTIFY, 5_000L, 2_000L
            ).join();
            List<DuplicateFinding> retry = repository.completeObservationEpochAndAudit(
                88L, true, DuplicateAction.NOTIFY, 5_000L, 2_001L
            ).join();

            assertEquals(1, first.size());
            assertEquals(itemUuid, first.getFirst().itemUuid());
            assertEquals("AB12CD", first.getFirst().code());
            assertEquals(DuplicateStatus.CONFIRMED, first.getFirst().status());
            assertEquals(2, first.getFirst().distinctLocations());
            assertEquals(DuplicateAction.NOTIFY, first.getFirst().action());
            assertTrue(retry.isEmpty());
            assertEquals(1, repository.countDuplicateFindings(itemUuid, 88L));
            assertEquals(1, repository.getStats().getDuplicatesDetected());
        }
    }

    @Test
    void completedEpochAuditsPlayerAndBlockContainerLocationsExactlyOnce() {
        UUID itemUuid = UUID.randomUUID();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("player-container-finding.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(item("AB12CD", itemUuid, "Thanh", Material.DIAMOND_SWORD));
            // Prior epoch: same identity at two locations, so the next epoch can confirm (C1).
            repository.recordObservation(new ItemObservation(
                itemUuid,
                "AB12CD",
                88L,
                new ObservationKey(HolderType.PLAYER, "player-a", 8),
                900L
            ));
            repository.recordObservation(new ItemObservation(
                itemUuid,
                "AB12CD",
                88L,
                new ObservationKey(
                    HolderType.CONTAINER,
                    "BLOCK:world-a:10:64:-7",
                    3
                ),
                901L
            ));
            assertTrue(repository.completeObservationEpochAndAudit(
                88L, true, DuplicateAction.NOTIFY, 5_000L, 1_500L
            ).join().isEmpty());
            repository.recordObservation(new ItemObservation(
                itemUuid,
                "AB12CD",
                89L,
                new ObservationKey(HolderType.PLAYER, "player-a", 8),
                1_000L
            ));
            repository.recordObservation(new ItemObservation(
                itemUuid,
                "AB12CD",
                89L,
                new ObservationKey(
                    HolderType.CONTAINER,
                    "BLOCK:world-a:10:64:-7",
                    3
                ),
                1_001L
            ));

            List<DuplicateFinding> first = repository.completeObservationEpochAndAudit(
                89L, true, DuplicateAction.NOTIFY, 5_000L, 2_000L
            ).join();
            List<DuplicateFinding> retry = repository.completeObservationEpochAndAudit(
                89L, true, DuplicateAction.NOTIFY, 5_000L, 2_001L
            ).join();

            assertEquals(1, first.size());
            assertEquals(DuplicateStatus.CONFIRMED, first.getFirst().status());
            assertEquals(2, first.getFirst().distinctLocations());
            assertEquals(DuplicateAction.NOTIFY, first.getFirst().action());
            assertTrue(retry.isEmpty());
            assertEquals(1, repository.countDuplicateFindings(itemUuid, 89L));
            assertEquals(1, repository.getStats().getDuplicatesDetected());
        }
    }

    @Test
    void duplicateFindingCooldownIsDurableAcrossEpochs() {
        UUID itemUuid = UUID.randomUUID();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("duplicate-cooldown.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(item("AB12CD", itemUuid, "Thanh", Material.DIAMOND_SWORD));

            // Prior epoch so the first detection below can confirm (C1, 2026-09-16).
            recordTwoLocations(repository, itemUuid, 89L);
            assertTrue(repository.completeObservationEpochAndAudit(
                89L, true, DuplicateAction.NOTIFY, 5_000L, 9_000L
            ).join().isEmpty());
            recordTwoLocations(repository, itemUuid, 90L);
            assertEquals(1, repository.completeObservationEpochAndAudit(
                90L, true, DuplicateAction.NOTIFY, 5_000L, 10_000L
            ).join().size());

            recordTwoLocations(repository, itemUuid, 91L);
            assertTrue(repository.completeObservationEpochAndAudit(
                91L, true, DuplicateAction.NOTIFY, 5_000L, 12_000L
            ).join().isEmpty());

            recordTwoLocations(repository, itemUuid, 92L);
            assertEquals(1, repository.completeObservationEpochAndAudit(
                92L, true, DuplicateAction.NOTIFY, 5_000L, 15_000L
            ).join().size());
            assertEquals(2, repository.getStats().getDuplicatesDetected());
        }
    }

    @Test
    void maximumPersistedEpochSurvivesObservationCleanup() {
        UUID itemUuid = UUID.randomUUID();
        Path database = tempDir.resolve("epoch-floor.db");
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            database
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(item("AB12CD", itemUuid, "Thanh", Material.DIAMOND_SWORD));
            recordTwoLocations(repository, itemUuid, 5_000L);
            repository.completeObservationEpochAndAudit(
                5_000L, true, DuplicateAction.NOTIFY, 5_000L, 10_000L
            ).join();
            repository.recordObservation(observation(
                itemUuid, "AB12CD", 5_001L, "player-a", 5, 10_001L
            ));
            repository.completeObservationEpoch(5_001L);
            owner.flush();
        }
        try (SqliteConnectionOwner reopened = new SqliteConnectionOwner(database)) {
            assertEquals(
                5_001L,
                new ItemSqliteRepository(reopened).getMaximumPersistedObservationEpoch()
            );
        }
    }

    @Test
    void completedEpochBoundsNewDuplicateFindings() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("bounded-findings.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            for (int index = 0; index < 65; index++) {
                String code = String.format("D%05d", index);
                UUID itemUuid = UUID.randomUUID();
                repository.saveItem(item(code, itemUuid, "Thanh", Material.DIAMOND_SWORD));
                // Prior epoch for the same identity, so the next epoch holds a confirmation (C1).
                repository.recordObservation(observation(
                    itemUuid, code, 5_999L, "player-a", index * 2, 9_000L
                ));
                repository.recordObservation(observation(
                    itemUuid, code, 5_999L, "player-a", index * 2 + 1, 9_001L
                ));
                repository.recordObservation(observation(
                    itemUuid, code, 6_000L, "player-a", index * 2, 10_000L
                ));
                repository.recordObservation(observation(
                    itemUuid, code, 6_000L, "player-a", index * 2 + 1, 10_001L
                ));
            }

            assertTrue(repository.completeObservationEpochAndAudit(
                5_999L, true, DuplicateAction.NOTIFY, 5_000L, 9_500L
            ).join().isEmpty());
            List<DuplicateFinding> findings = repository.completeObservationEpochAndAudit(
                6_000L, true, DuplicateAction.NOTIFY, 5_000L, 11_000L
            ).join();

            assertEquals(64, findings.size());
            assertEquals(65, repository.countDuplicateFindingsForEpoch(6_000L));
            assertEquals(65, repository.getStats().getDuplicatesDetected());
        }
    }

    /**
     * A single duplicated item is re-detected on every scan epoch, so the raw detection counter keeps
     * growing. The distinct identity count must stay at one, otherwise the statistic reads as many
     * duplicated items.
     */
    @Test
    void repeatedDetectionsOfOneItemCountAsOneDistinctDuplicateIdentity() {
        UUID itemUuid = UUID.randomUUID();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("distinct-one-item.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(item("AB12CD", itemUuid, "Thanh", Material.DIAMOND_SWORD));
            // One seeding epoch first: it cannot confirm on its own, and the twelve that follow
            // each can, which is what this test counts (C1, 2026-09-16).
            recordTwoLocations(repository, itemUuid, 6_999L);
            assertTrue(repository.completeObservationEpochAndAudit(
                6_999L, true, DuplicateAction.NOTIFY, 0L, 19_000L
            ).join().isEmpty());
            for (int epoch = 0; epoch < 12; epoch++) {
                recordTwoLocations(repository, itemUuid, 7_000L + epoch);
                assertEquals(1, repository.completeObservationEpochAndAudit(
                    7_000L + epoch, true, DuplicateAction.NOTIFY, 0L, 20_000L + epoch
                ).join().size());
            }

            assertEquals(12, repository.getStats().getDuplicatesDetected());
            assertEquals(1, repository.countDistinctDuplicateItems());
            assertEquals(1, repository.getStats().getDistinctDuplicateItems());
            assertEquals(1, repository.getStatsAsync().join().getDistinctDuplicateItems());
        }
    }

    @Test
    void distinctDuplicateIdentityCountFollowsTheNumberOfDuplicatedItems() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("distinct-many-items.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            List<UUID> duplicated = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                String code = String.format("D%05d", index);
                UUID itemUuid = UUID.randomUUID();
                duplicated.add(itemUuid);
                repository.saveItem(item(code, itemUuid, "Thanh", Material.DIAMOND_SWORD));
                // Prior epoch so the next one can confirm each identity (C1, 2026-09-16).
                repository.recordObservation(observation(
                    itemUuid, code, 7_499L, "player-a", index * 2, 9_000L
                ));
                repository.recordObservation(observation(
                    itemUuid, code, 7_499L, "player-a", index * 2 + 1, 9_001L
                ));
                repository.recordObservation(observation(
                    itemUuid, code, 7_500L, "player-a", index * 2, 10_000L
                ));
                repository.recordObservation(observation(
                    itemUuid, code, 7_500L, "player-a", index * 2 + 1, 10_001L
                ));
            }
            assertTrue(repository.completeObservationEpochAndAudit(
                7_499L, true, DuplicateAction.NOTIFY, 0L, 9_500L
            ).join().isEmpty());
            assertEquals(3, repository.completeObservationEpochAndAudit(
                7_500L, true, DuplicateAction.NOTIFY, 0L, 11_000L
            ).join().size());

            UUID reDetected = duplicated.getFirst();
            repository.recordObservation(observation(
                reDetected, "D00000", 7_501L, "player-b", 0, 12_000L
            ));
            repository.recordObservation(observation(
                reDetected, "D00000", 7_501L, "player-b", 1, 12_001L
            ));
            assertEquals(1, repository.completeObservationEpochAndAudit(
                7_501L, true, DuplicateAction.NOTIFY, 0L, 12_500L
            ).join().size());

            assertEquals(4, repository.getStats().getDuplicatesDetected());
            assertEquals(3, repository.countDistinctDuplicateItems());
            assertEquals(3, repository.getStats().getDistinctDuplicateItems());
        }
    }

    @Test
    void statisticsWithoutDuplicateFindingsReportZeroDetectionsAndZeroDistinctItems() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("no-duplicate-findings.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(item("AB12CD", UUID.randomUUID(), "Thanh", Material.DIAMOND_SWORD));

            assertEquals(0, repository.getStats().getDuplicatesDetected());
            assertEquals(0, repository.countDistinctDuplicateItems());
            assertEquals(0, repository.getStats().getDistinctDuplicateItems());
            assertEquals(0, repository.getStatsAsync().join().getDistinctDuplicateItems());
        }
    }

    @Test
    void snapshotRequiresTrackedCodeAndSurvivesRestart() {
        Path database = tempDir.resolve("snapshots.db");
        ItemSnapshotCodec codec = new ItemSnapshotCodec(1024);
        ItemSnapshot snapshot = codec.capture(new byte[] {9, 8, 7, 6});

        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(database)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(item(
                "AB12CD",
                UUID.randomUUID(),
                "Thanh",
                Material.DIAMOND_SWORD
            ));
            repository.saveSnapshot("AB12CD", snapshot, 2_000L);
            owner.flush();

            assertThrows(
                IllegalStateException.class,
                () -> repository.saveSnapshot("MISSING", snapshot, 2_001L)
            );
        }

        try (SqliteConnectionOwner reopened = new SqliteConnectionOwner(database)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(reopened);
            ItemSnapshot loaded = repository.getSnapshot("AB12CD").orElseThrow();
            assertEquals(snapshot.version(), loaded.version());
            assertArrayEquals(snapshot.payload(), loaded.payload());
            assertArrayEquals(snapshot.sha256(), loaded.sha256());
            assertTrue(repository.getSnapshot("MISSING").isEmpty());
        }
    }

    @Test
    void itemAndSnapshotPersistAtomicallyAndRejectIdentityRebind() {
        Path database = tempDir.resolve("atomic-snapshot.db");
        List<Throwable> failures = new ArrayList<>();
        UUID canonicalUuid = UUID.randomUUID();
        ItemSnapshotCodec codec = new ItemSnapshotCodec(1024);
        ItemSnapshot first = codec.capture(new byte[] {1, 2, 3});
        ItemSnapshot conflicting = codec.capture(new byte[] {4, 5, 6});

        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            database,
            failures::add
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItemWithSnapshot(
                item("AB12CD", canonicalUuid, "Thanh", Material.DIAMOND_SWORD),
                first,
                2_000L
            );
            assertThrows(
                IllegalStateException.class,
                () -> repository.saveItemWithSnapshot(
                    item("AB12CD", UUID.randomUUID(), "Other", Material.NETHERITE_SWORD),
                    conflicting,
                    3_000L
                )
            );

            assertEquals(canonicalUuid, repository.getItem("AB12CD").orElseThrow().getItemUuid());
            assertArrayEquals(first.payload(), repository.getSnapshot("AB12CD").orElseThrow().payload());
            assertTrue(failures.isEmpty());
        }
    }

    private ItemData item(String code, UUID uuid, String owner, Material material) {
        ItemData item = new ItemData(code, uuid);
        item.setOwnerUuid(UUID.randomUUID());
        item.setOwnerName(owner);
        item.setMaterial(material);
        item.setItemName("Guarded Sword");
        item.setItemLore("line one|line two");
        item.setCreatedAt(1_000L);
        item.setLastSeenAt(1_000L);
        item.setLastAction("SPAWN");
        item.setDetectionCount(1);
        item.setLastLocation("world (0, 64, 0)");
        return item;
    }

    private ItemObservation observation(
        UUID itemUuid,
        String code,
        long scanEpoch,
        String holderId,
        int slot,
        long observedAt
    ) {
        return new ItemObservation(
            itemUuid,
            code,
            scanEpoch,
            new ObservationKey(HolderType.PLAYER, holderId, slot),
            observedAt
        );
    }

    private void recordTwoLocations(
        ItemSqliteRepository repository,
        UUID itemUuid,
        long scanEpoch
    ) {
        repository.recordObservation(observation(
            itemUuid, "AB12CD", scanEpoch, "player-a", 5, scanEpoch
        ));
        repository.recordObservation(observation(
            itemUuid, "AB12CD", scanEpoch, "player-a", 8, scanEpoch + 1L
        ));
    }

    private ItemHistory history(String code, UUID itemUuid, UUID ownerUuid, long timestamp) {
        ItemHistory history = new ItemHistory(
            code, itemUuid, "PICKUP", "Thanh", ownerUuid, "world (1, 2, 3)"
        );
        history.setTimestamp(timestamp);
        return history;
    }
}
