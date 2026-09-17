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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagPublicationRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void prepareIsDurableButNotVisibleAsPublishedItem() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("prepare.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            TagPublication publication = publication("AB12CD", "ENTITY:item-1");

            repository.prepareTagPublication(publication);

            assertEquals(
                TagPublicationState.PREPARED,
                repository.getTagPublication(publication.publicationId()).orElseThrow().state()
            );
            assertTrue(repository.getItem("AB12CD").isEmpty());
            assertTrue(repository.getSnapshot("AB12CD").isEmpty());
        }
    }

    @Test
    void publishAtomicallyMaterializesCanonicalItemAndSnapshot() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("publish.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            TagPublication publication = publication("AB12CD", "ENTITY:item-1");
            repository.prepareTagPublication(publication);

            assertTrue(repository.publishTagPublication(publication.publicationId(), 2_000L));

            assertEquals(
                TagPublicationState.PUBLISHED,
                repository.getTagPublication(publication.publicationId()).orElseThrow().state()
            );
            assertEquals(
                publication.item().getItemUuid(),
                repository.getItem("AB12CD").orElseThrow().getItemUuid()
            );
            assertEquals(
                publication.snapshot().version(),
                repository.getSnapshot("AB12CD").orElseThrow().version()
            );
            assertFalse(repository.publishTagPublication(publication.publicationId(), 2_001L));
        }
    }

    @Test
    void abortKeepsAuditWithoutMaterializingItem() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("abort.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            TagPublication publication = publication("AB12CD", "PLAYER_SLOT:p:5");
            repository.prepareTagPublication(publication);

            assertTrue(repository.abortTagPublication(
                publication.publicationId(),
                2_000L,
                "SOURCE_CHANGED"
            ));

            TagPublication aborted = repository.getTagPublication(
                publication.publicationId()
            ).orElseThrow();
            assertEquals(TagPublicationState.ABORTED, aborted.state());
            assertEquals("SOURCE_CHANGED", aborted.detail());
            assertTrue(repository.getItem("AB12CD").isEmpty());
            assertTrue(repository.getSnapshot("AB12CD").isEmpty());
        }
    }

    @Test
    void restartRetainsPreparedAndSourceLockRejectsConcurrentRequest() {
        Path database = tempDir.resolve("restart.db");
        TagPublication publication = publication("AB12CD", "ENTITY:item-1");
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(database)) {
            new ItemSqliteRepository(owner).prepareTagPublication(publication);
        }

        try (SqliteConnectionOwner reopened = new SqliteConnectionOwner(database)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(reopened);
            assertEquals(
                TagPublicationState.PREPARED,
                repository.getTagPublication(publication.publicationId()).orElseThrow().state()
            );
            assertThrows(
                IllegalStateException.class,
                () -> repository.prepareTagPublication(
                    publication("EF34GH", publication.sourceKey())
                )
            );
        }
    }

    @Test
    void asyncReserveReusesSameDigestAndReplacesChangedSource() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("reserve.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            TagPublication first = publication("AB12CD", "ENTITY:item-1");

            TagPublication reserved = repository.reserve(first).get(2, TimeUnit.SECONDS);
            TagPublication reused = repository.reserve(
                publication("EF34GH", first.sourceKey())
            ).get(2, TimeUnit.SECONDS);

            assertEquals(reserved.publicationId(), reused.publicationId());

            TagPublication changed = publication("IJ56KL", first.sourceKey());
            byte[] changedDigest = changed.sourceDigest();
            changedDigest[0] = 1;
            changed = new TagPublication(
                changed.publicationId(), changed.sourceKey(), changedDigest,
                changed.item(), changed.snapshot(), changed.capturedAt(),
                changed.state(), changed.createdAt(), changed.updatedAt(),
                changed.detail()
            );

            TagPublication replaced = repository.reserve(changed).get(2, TimeUnit.SECONDS);

            assertEquals(changed.publicationId(), replaced.publicationId());
            assertEquals(
                TagPublicationState.ABORTED,
                repository.getTagPublication(first.publicationId()).orElseThrow().state()
            );
        }
    }

    @Test
    void reconcilePublishesPreparedIdentityAfterPhysicalWriteCrashWindow() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("reconcile.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            TagPublication publication = publication("AB12CD", "ENTITY:item-1");
            repository.reserve(publication).get(2, TimeUnit.SECONDS);

            assertTrue(repository.reconcile(
                receipt(publication, publication.sourceKey(), publication.snapshot().sha256()),
                2_000L
            ).get(2, TimeUnit.SECONDS));

            assertEquals(
                TagPublicationState.PUBLISHED,
                repository.getTagPublication(publication.publicationId()).orElseThrow().state()
            );
            assertTrue(repository.getItem(publication.item().getCode()).isPresent());
            assertTrue(repository.getSnapshot(publication.item().getCode()).isPresent());
        }
    }

    @Test
    void reconcileRejectsReceiptFromDifferentPhysicalSource() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("reconcile-wrong-source.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            TagPublication publication = publication("AB12CD", "PLAYER_SLOT:owner:8");
            repository.reserve(publication).get(2, TimeUnit.SECONDS);

            assertFalse(repository.reconcile(
                receipt(publication, "PLAYER_SLOT:owner:5", publication.snapshot().sha256()),
                2_000L
            ).get(2, TimeUnit.SECONDS));

            assertEquals(
                TagPublicationState.PREPARED,
                repository.getTagPublication(publication.publicationId()).orElseThrow().state()
            );
            assertTrue(repository.getItem(publication.item().getCode()).isEmpty());
        }
    }

    @Test
    void reconcileRejectsReceiptWhenPhysicalTaggedBytesChanged() throws Exception {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("reconcile-wrong-digest.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            TagPublication publication = publication("AB12CD", "PLAYER_SLOT:owner:8");
            repository.reserve(publication).get(2, TimeUnit.SECONDS);
            byte[] changedDigest = publication.snapshot().sha256();
            changedDigest[0] ^= 1;

            assertFalse(repository.reconcile(
                receipt(publication, publication.sourceKey(), changedDigest),
                2_000L
            ).get(2, TimeUnit.SECONDS));

            assertEquals(
                TagPublicationState.PREPARED,
                repository.getTagPublication(publication.publicationId()).orElseThrow().state()
            );
            assertTrue(repository.getSnapshot(publication.item().getCode()).isEmpty());
        }
    }

    @Test
    void stalePublishTimestampCannotMaterializeCanonicalRows() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("stale-publish.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            TagPublication publication = publication("AB12CD", "ENTITY:item-1");
            repository.prepareTagPublication(publication);

            assertFalse(repository.publishTagPublication(
                publication.publicationId(),
                publication.updatedAt() - 1L
            ));

            assertTrue(repository.getItem(publication.item().getCode()).isEmpty());
            assertTrue(repository.getSnapshot(publication.item().getCode()).isEmpty());
            assertEquals(
                TagPublicationState.PREPARED,
                repository.getTagPublication(publication.publicationId()).orElseThrow().state()
            );
        }
    }

    private TagPublication publication(String code, String sourceKey) {
        UUID itemUuid = UUID.randomUUID();
        ItemData item = new ItemData(code, itemUuid);
        item.setMaterial(Material.DIAMOND_SWORD);
        item.setItemName("Guarded Sword");
        item.setItemLore("line one|line two");
        item.setCreatedAt(1_000L);
        item.setLastSeenAt(1_000L);
        item.setLastAction("SPAWN");
        item.setDetectionCount(1);
        item.setLastLocation("world (0, 64, 0)");
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

    private TagReconciliationReceipt receipt(
        TagPublication publication,
        String sourceKey,
        byte[] taggedDigest
    ) {
        return new TagReconciliationReceipt(
            publication.item().getCode(),
            publication.item().getItemUuid(),
            sourceKey,
            taggedDigest
        );
    }
}
