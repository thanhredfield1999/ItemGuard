package com.itemguard.persistence;

import com.itemguard.data.ItemData;
import com.itemguard.snapshot.ItemSnapshotCodec;
import com.itemguard.tracking.TagIdentityCollisionException;
import com.itemguard.tracking.TagPublication;
import com.itemguard.tracking.TagPublicationState;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TagIdentityCollisionRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void canonicalCollisionIsTypedAndNeverCreatesPublication() throws Exception {
        try (var owner = new SqliteConnectionOwner(tempDir.resolve("canonical.db"))) {
            var repository = new ItemSqliteRepository(owner);
            var canonical = publication("AB12CD", UUID.randomUUID(), "first", new byte[32]);
            repository.saveItemWithSnapshot(canonical.item(), canonical.snapshot(), 1L);
            for (boolean collideCode : new boolean[] {true, false}) {
                var proposed = publication(collideCode ? "AB12CD" : "EF34GH",
                    collideCode ? UUID.randomUUID() : canonical.item().getItemUuid(), "second", new byte[32]);
                var failure = assertThrows(ExecutionException.class,
                    () -> repository.reserve(proposed).get(2, TimeUnit.SECONDS));
                assertInstanceOf(TagIdentityCollisionException.class, failure.getCause());
                assertTrue(repository.getTagPublication(proposed.publicationId()).isEmpty());
            }
            assertEquals(canonical.item().getItemUuid(), repository.getItem("AB12CD").orElseThrow().getItemUuid());
        }
    }

    @Test
    void everyJournalStateReservesCodeAndUuid() throws Exception {
        for (var state : TagPublicationState.values()) {
            try (var owner = new SqliteConnectionOwner(tempDir.resolve(state + ".db"))) {
                var repository = new ItemSqliteRepository(owner);
                var first = publication("AB12CD", UUID.randomUUID(), "first", new byte[32]);
                repository.reserve(first).get(2, TimeUnit.SECONDS);
                if (state == TagPublicationState.ABORTED) repository.abort(first.publicationId(), 2L, "test").get(2, TimeUnit.SECONDS);
                if (state == TagPublicationState.PUBLISHED) repository.publish(first.publicationId(), 2L).get(2, TimeUnit.SECONDS);
                for (boolean collideCode : new boolean[] {true, false}) {
                    var next = publication(collideCode ? "AB12CD" : "EF34GH",
                        collideCode ? UUID.randomUUID() : first.item().getItemUuid(), "second", new byte[32]);
                    var failure = assertThrows(ExecutionException.class, () -> repository.reserve(next).get(2, TimeUnit.SECONDS));
                    assertInstanceOf(TagIdentityCollisionException.class, failure.getCause());
                    assertTrue(repository.getTagPublication(next.publicationId()).isEmpty());
                    assertEquals(state, repository.getTagPublication(first.publicationId()).orElseThrow().state());
                }
            }
        }
    }

    @Test
    void synchronousPrepareSharesCanonicalCollisionGate() {
        try (var owner = new SqliteConnectionOwner(tempDir.resolve("prepare-collision.db"))) {
            var repository = new ItemSqliteRepository(owner);
            var first = publication("AB12CD", UUID.randomUUID(), "first", new byte[32]);
            repository.saveItemWithSnapshot(first.item(), first.snapshot(), 1L);
            var next = publication("AB12CD", UUID.randomUUID(), "second", new byte[32]);
            assertThrows(TagIdentityCollisionException.class, () -> repository.prepareTagPublication(next));
            assertTrue(repository.getTagPublication(next.publicationId()).isEmpty());
        }
    }

    @Test
    void changedSourceAbortRollsBackWhenReplacementCollides() throws Exception {
        try (var owner = new SqliteConnectionOwner(tempDir.resolve("rollback.db"))) {
            var repository = new ItemSqliteRepository(owner);
            var first = publication("AB12CD", UUID.randomUUID(), "same-source", new byte[32]);
            var occupied = publication("EF34GH", UUID.randomUUID(), "occupied", new byte[32]);
            repository.reserve(first).get(2, TimeUnit.SECONDS);
            repository.reserve(occupied).get(2, TimeUnit.SECONDS);
            byte[] changedDigest = new byte[32]; changedDigest[0] = 1;
            var changed = publication("EF34GH", UUID.randomUUID(), first.sourceKey(), changedDigest);
            var failure = assertThrows(ExecutionException.class, () -> repository.reserve(changed).get(2, TimeUnit.SECONDS));
            assertInstanceOf(TagIdentityCollisionException.class, failure.getCause());
            assertEquals(TagPublicationState.PREPARED, repository.getTagPublication(first.publicationId()).orElseThrow().state());
            assertTrue(repository.getTagPublication(changed.publicationId()).isEmpty());
            var fresh = publication("IJ56KL", UUID.randomUUID(), first.sourceKey(), changedDigest);
            assertEquals(fresh, repository.reserve(fresh).get(2, TimeUnit.SECONDS));
            assertEquals(TagPublicationState.ABORTED, repository.getTagPublication(first.publicationId()).orElseThrow().state());
        }
    }

    @Test
    void clockRegressionCannotLeaveTwoPreparedRowsForOneSource() throws Exception {
        try (var owner = new SqliteConnectionOwner(tempDir.resolve("clock.db"))) {
            var repository = new ItemSqliteRepository(owner);
            var first = publication("AB12CD", UUID.randomUUID(), "same-source", new byte[32]);
            first = new TagPublication(first.publicationId(), first.sourceKey(), first.sourceDigest(), first.item(),
                first.snapshot(), 10L, first.state(), 10L, 10L, null);
            repository.reserve(first).get(2, TimeUnit.SECONDS);
            byte[] digest = new byte[32]; digest[0] = 1;
            var changed = publication("EF34GH", UUID.randomUUID(), first.sourceKey(), digest);
            assertThrows(ExecutionException.class, () -> repository.reserve(changed).get(2, TimeUnit.SECONDS));
            assertEquals(TagPublicationState.PREPARED, repository.getTagPublication(first.publicationId()).orElseThrow().state());
            assertTrue(repository.getTagPublication(changed.publicationId()).isEmpty(), "unique PREPARED source constraint must rollback insertion");
        }
    }

    private TagPublication publication(String code, UUID identity, String source, byte[] digest) {
        var item = new ItemData(code, identity);
        item.setMaterial(Material.DIAMOND_SWORD);
        item.setCreatedAt(1L);
        item.setLastSeenAt(1L);
        return new TagPublication(UUID.randomUUID(), source, digest, item,
            new ItemSnapshotCodec(1024).capture(new byte[] {1, 2, 3}),
            1L, TagPublicationState.PREPARED, 1L, 1L, null);
    }
}
