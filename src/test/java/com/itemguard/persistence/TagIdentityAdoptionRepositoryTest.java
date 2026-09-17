package com.itemguard.persistence;

import com.itemguard.tracking.TagPublication;
import com.itemguard.tracking.TagReconciliationReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for C3 (review 2026-09-16): an item carrying a COMPLETE identity whose canonical row
 * is gone must not be destroyed.
 *
 * <p>Before this, the reconciliation path returned false for everything that was neither a
 * canonical row nor an exact in-flight publication match. The pickup listener cancels on a false
 * readiness, so the item could not be picked up, and a ground item that cannot be picked up
 * despawns. The database lost one row and the player lost the whole item — an asymmetry that is
 * the wrong way round for a plugin whose job is to look after items.
 *
 * <p>Adoption is deliberately narrow (Thanh, 2026-09-16): it applies only when the journal has
 * never seen the code or the item uuid at all. If the journal does know the identity, something
 * really is wrong with it and the old fail-closed answer stands.
 */
class TagIdentityAdoptionRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void identityTheJournalNeverIssuedIsAdoptedInsteadOfBlockingTheItemForever() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("adopt.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            UUID itemUuid = UUID.randomUUID();
            var unknown = new TagReconciliationReceipt(
                "ADPT01",
                itemUuid,
                "ENTITY:" + itemUuid,
                new byte[32]
            );

            assertFalse(
                repository.getItem("ADPT01").isPresent(),
                "precondition: the database does not know this identity"
            );

            assertTrue(
                repository.reconcile(unknown, 1_000L).get(2, TimeUnit.SECONDS),
                "an identity the database never issued must be adopted, not refused forever"
            );

            var adopted = repository.getItem("ADPT01").orElseThrow();
            assertEquals(itemUuid, adopted.getItemUuid());
            assertEquals(
                "ADOPTED",
                adopted.getLastAction(),
                "an adopted row must say so, or it is indistinguishable from a real issuance"
            );
            assertEquals(
                1,
                repository.getHistoryCount("ADPT01"),
                "adoption must leave an audit row, so staff can see the identity was never issued"
            );
            assertEquals(1, repository.getHistory("ADPT01", 10).size());
            assertEquals(
                0L,
                adopted.getCreatedAt(),
                "H4 (review 2026-09-17): created_at is the durable marker of an adopted row. "
                    + "Writing the adoption time there made /ig check report a false creation date, "
                    + "and last_action stops saying ADOPTED as soon as the item is picked up"
            );
        }
    }

    @Test
    void identityTheJournalKnowsIsStillRefusedWhenItsCanonicalRowIsGone() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("known.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            UUID itemUuid = UUID.randomUUID();
            // The journal minted this code: a publication exists, in a state the reconciliation
            // cannot use. That is the suspicious case, and it must not be adopted away.
            var item = new com.itemguard.data.ItemData("KNOWN1", itemUuid);
            var snapshot = new com.itemguard.snapshot.ItemSnapshotCodec(1024)
                .capture(new byte[]{1, 2, 3});
            TagPublication publication = new TagPublication(
                UUID.randomUUID(),
                "ENTITY:" + itemUuid,
                new byte[32],
                item,
                snapshot,
                1_000L,
                com.itemguard.tracking.TagPublicationState.PREPARED,
                1_000L,
                1_000L,
                null
            );
            repository.reserve(publication).get(2, TimeUnit.SECONDS);

            var relocated = new TagReconciliationReceipt(
                "KNOWN1",
                itemUuid,
                "PLAYER_SLOT:11111111-1111-1111-1111-111111111111:8",
                new byte[32]
            );

            assertFalse(
                repository.reconcile(relocated, 2_000L).get(2, TimeUnit.SECONDS),
                "a code the journal already minted must stay fail-closed, not be adopted"
            );
            assertTrue(
                repository.getItem("KNOWN1").isEmpty(),
                "no canonical row may be created for an identity the journal knows"
            );
        }
    }
}
