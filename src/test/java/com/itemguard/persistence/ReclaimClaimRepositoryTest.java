package com.itemguard.persistence;

import com.itemguard.reclaim.ReclaimClaim;
import com.itemguard.reclaim.ReclaimClaimState;
import com.itemguard.data.ItemData;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReclaimClaimRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void onlyOneActiveClaimCanBeReservedAndCommitted() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("claims.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            UUID playerUuid = UUID.randomUUID();
            repository.saveItem(trackedItem(playerUuid));
            owner.flush();
            ReclaimClaim first = claim(playerUuid, "token-one", 1_000L);
            ReclaimClaim concurrent = claim(playerUuid, "token-two", 1_001L);

            assertTrue(repository.beginReclaimClaim(first));
            assertFalse(repository.beginReclaimClaim(concurrent));
            assertTrue(repository.transitionReclaimClaim(
                first.claimId(),
                ReclaimClaimState.PENDING,
                ReclaimClaimState.PREPARED,
                2_000L,
                null
            ));
            assertTrue(repository.transitionReclaimClaim(
                first.claimId(),
                ReclaimClaimState.PREPARED,
                ReclaimClaimState.COMMITTED,
                2_001L,
                null
            ));
            assertFalse(repository.transitionReclaimClaim(
                first.claimId(),
                ReclaimClaimState.PREPARED,
                ReclaimClaimState.COMMITTED,
                2_002L,
                null
            ));
            assertFalse(repository.beginReclaimClaim(
                claim(playerUuid, "token-three", 3_000L)
            ));
            assertThrows(IllegalArgumentException.class, () ->
                repository.transitionReclaimClaim(
                    first.claimId(),
                    ReclaimClaimState.COMMITTED,
                    ReclaimClaimState.PENDING,
                    3_001L,
                    null
                )
            );
        }
    }

    @Test
    void deniedClaimReleasesActiveLockAndRestartPreservesJournal() {
        Path database = tempDir.resolve("claim-restart.db");
        UUID playerUuid = UUID.randomUUID();
        ReclaimClaim denied = claim(playerUuid, "denied-token", 1_000L);
        ReclaimClaim retry = claim(playerUuid, "retry-token", 2_000L);

        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(database)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(trackedItem(playerUuid));
            owner.flush();
            assertTrue(repository.beginReclaimClaim(denied));
            assertTrue(repository.transitionReclaimClaim(
                denied.claimId(),
                ReclaimClaimState.PENDING,
                ReclaimClaimState.DENIED,
                1_100L,
                "PLAYER_VAULTS_UNAVAILABLE"
            ));
            assertTrue(repository.beginReclaimClaim(retry));
        }

        try (SqliteConnectionOwner reopened = new SqliteConnectionOwner(database)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(reopened);
            assertEquals(
                ReclaimClaimState.DENIED,
                repository.getReclaimClaim(denied.claimId()).orElseThrow().state()
            );
            assertEquals(
                "PLAYER_VAULTS_UNAVAILABLE",
                repository.getReclaimClaim(denied.claimId()).orElseThrow().detail()
            );
            assertEquals(
                ReclaimClaimState.PENDING,
                repository.getReclaimClaim(retry.claimId()).orElseThrow().state()
            );
        }
    }

    @Test
    void startupRecoveryDeniesPendingButPreservesPreparedLock() {
        Path database = tempDir.resolve("claim-recovery.db");
        UUID pendingPlayer = UUID.randomUUID();
        UUID preparedPlayer = UUID.randomUUID();
        ReclaimClaim pending = claim(pendingPlayer, "pending-token", 1_000L);
        ReclaimClaim preparedPending = new ReclaimClaim(
            UUID.randomUUID(),
            "prepared-token",
            preparedPlayer,
            "EF34GH",
            ReclaimClaimState.PENDING,
            1_000L,
            1_000L,
            null
        );

        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(database)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.saveItem(trackedItem(pendingPlayer, "AB12CD"));
            repository.saveItem(trackedItem(preparedPlayer, "EF34GH"));
            owner.flush();
            assertTrue(repository.beginReclaimClaim(pending));
            assertTrue(repository.beginReclaimClaim(preparedPending));
            assertTrue(repository.transitionReclaimClaim(
                preparedPending.claimId(),
                ReclaimClaimState.PENDING,
                ReclaimClaimState.PREPARED,
                1_100L,
                null
            ));
        }

        try (SqliteConnectionOwner reopened = new SqliteConnectionOwner(database)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(reopened);

            assertEquals(1, repository.recoverPendingReclaimClaims(
                2_000L,
                "STARTUP_RECOVERY"
            ));
            assertEquals(
                ReclaimClaimState.DENIED,
                repository.getReclaimClaim(pending.claimId()).orElseThrow().state()
            );
            assertEquals(
                "STARTUP_RECOVERY",
                repository.getReclaimClaim(pending.claimId()).orElseThrow().detail()
            );
            assertEquals(
                ReclaimClaimState.PREPARED,
                repository.getReclaimClaim(preparedPending.claimId()).orElseThrow().state()
            );
            assertTrue(repository.beginReclaimClaim(
                claim(pendingPlayer, "retry-after-recovery", 2_001L)
            ));
            assertFalse(repository.beginReclaimClaim(new ReclaimClaim(
                UUID.randomUUID(),
                "prepared-retry",
                preparedPlayer,
                "EF34GH",
                ReclaimClaimState.PENDING,
                2_001L,
                2_001L,
                null
            )));
        }
    }

    private ReclaimClaim claim(UUID playerUuid, String token, long requestedAt) {
        return new ReclaimClaim(
            UUID.randomUUID(),
            token,
            playerUuid,
            "AB12CD",
            ReclaimClaimState.PENDING,
            requestedAt,
            requestedAt,
            null
        );
    }

    private ItemData trackedItem(UUID ownerUuid) {
        return trackedItem(ownerUuid, "AB12CD");
    }

    private ItemData trackedItem(UUID ownerUuid, String code) {
        ItemData item = new ItemData(code, UUID.randomUUID());
        item.setOwnerUuid(ownerUuid);
        item.setOwnerName("Thanh");
        item.setMaterial(Material.DIAMOND_SWORD);
        item.setCreatedAt(500L);
        item.setLastSeenAt(500L);
        return item;
    }
}