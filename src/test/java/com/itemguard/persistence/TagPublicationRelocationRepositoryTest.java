package com.itemguard.persistence;

import com.itemguard.data.ItemData;
import com.itemguard.snapshot.ItemSnapshot;
import com.itemguard.snapshot.ItemSnapshotCodec;
import com.itemguard.tracking.TagPublication;
import com.itemguard.tracking.TagPublicationState;
import com.itemguard.tracking.TagReconciliationReceipt;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagPublicationRelocationRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void sameTaggedBytesAtDifferentPhysicalSourceCannotPublishPreparedIdentity() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("reconcile-relocation.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            TagPublication publication = publication("AB12CD", "ENTITY:item-1");
            repository.reserve(publication).get(2, TimeUnit.SECONDS);
            TagReconciliationReceipt relocated = new TagReconciliationReceipt(
                publication.item().getCode(),
                publication.item().getItemUuid(),
                "PLAYER_SLOT:11111111-1111-1111-1111-111111111111:8",
                publication.snapshot().sha256()
            );

            assertFalse(repository.reconcile(relocated, 2_000L).get(2, TimeUnit.SECONDS));

            TagPublication prepared = repository.getTagPublication(
                publication.publicationId()
            ).orElseThrow();
            assertEquals(TagPublicationState.PREPARED, prepared.state());
            assertEquals(publication.sourceKey(), prepared.sourceKey());
            assertTrue(repository.getItem(publication.item().getCode()).isEmpty());
        }
    }

    @Test
    void anchoredRelocationRejectsChangedTaggedBytes() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("reconcile-relocation-wrong-digest.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            TagPublication publication = publication("AB12CD", "ENTITY:item-1");
            repository.reserve(publication).get(2, TimeUnit.SECONDS);
            byte[] changedDigest = publication.snapshot().sha256();
            changedDigest[0] ^= 1;
            TagReconciliationReceipt relocated = new TagReconciliationReceipt(
                publication.item().getCode(),
                publication.item().getItemUuid(),
                "PLAYER_SLOT:11111111-1111-1111-1111-111111111111:8",
                changedDigest
            );

            assertFalse(repository.reconcile(relocated, 2_000L).get(2, TimeUnit.SECONDS));

            TagPublication prepared = repository.getTagPublication(
                publication.publicationId()
            ).orElseThrow();
            assertEquals(TagPublicationState.PREPARED, prepared.state());
            assertEquals(publication.sourceKey(), prepared.sourceKey());
            assertTrue(repository.getItem(publication.item().getCode()).isEmpty());
        }
    }

    @Test
    void anchoredRelocationRejectsOccupiedPreparedDestination() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("reconcile-relocation-occupied.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            TagPublication publication = publication("AB12CD", "ENTITY:item-1");
            TagPublication occupied = publication("EF34GH", "PLAYER_SLOT:11111111-1111-1111-1111-111111111111:8");
            repository.reserve(publication).get(2, TimeUnit.SECONDS);
            repository.reserve(occupied).get(2, TimeUnit.SECONDS);
            TagReconciliationReceipt relocated = new TagReconciliationReceipt(
                publication.item().getCode(),
                publication.item().getItemUuid(),
                occupied.sourceKey(),
                publication.snapshot().sha256()
            );

            assertFalse(repository.reconcile(relocated, 2_000L).get(2, TimeUnit.SECONDS));

            assertEquals(
                publication.sourceKey(),
                repository.getTagPublication(publication.publicationId()).orElseThrow().sourceKey()
            );
            assertEquals(
                TagPublicationState.PREPARED,
                repository.getTagPublication(occupied.publicationId()).orElseThrow().state()
            );
        }
    }

    @Test
    void relocationAuthorityRejectsNonPhysicalDestinationKey() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("reconcile-relocation-virtual.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            TagPublication publication = publication("AB12CD", "ENTITY:item-1");
            repository.reserve(publication).get(2, TimeUnit.SECONDS);
            TagReconciliationReceipt virtual = new TagReconciliationReceipt(
                publication.item().getCode(),
                publication.item().getItemUuid(),
                "VIRTUAL_GUI:inventory:8",
                publication.snapshot().sha256()
            );

            assertFalse(repository.reconcile(virtual, 2_000L).get(2, TimeUnit.SECONDS));
            assertEquals(
                publication.sourceKey(),
                repository.getTagPublication(publication.publicationId()).orElseThrow().sourceKey()
            );
        }
    }

    private TagPublication publication(String code, String sourceKey) {
        ItemData item = new ItemData(code, UUID.randomUUID());
        item.setMaterial(Material.DIAMOND_SWORD);
        item.setCreatedAt(1_000L);
        item.setLastSeenAt(1_000L);
        ItemSnapshot snapshot = new ItemSnapshotCodec(1024).capture(new byte[] {1, 2, 3});
        return new TagPublication(
            UUID.randomUUID(),
            sourceKey,
            new byte[32],
            item,
            snapshot,
            1_000L,
            TagPublicationState.PREPARED,
            1_000L,
            1_000L,
            null
        );
    }
}
