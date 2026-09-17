package com.itemguard.persistence;

import com.itemguard.search.ItemSearchMode;
import com.itemguard.search.ItemSearchRequest;
import com.itemguard.search.ItemSearchState;
import com.itemguard.dupe.HolderType;
import com.itemguard.dupe.ItemObservation;
import com.itemguard.dupe.ObservationKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchRequestRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void activeRequestCannotBeOverwrittenButCanRestartAfterStop() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("search.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            ItemSearchRequest find = request("AB12CD", ItemSearchMode.FIND, 1_000L, 5_000L);
            ItemSearchRequest take = request("AB12CD", ItemSearchMode.TAKE, 2_000L, 8_000L);

            assertTrue(repository.startSearchRequest(find));
            assertFalse(repository.startSearchRequest(take));
            assertEquals(ItemSearchMode.FIND,
                repository.getSearchRequest("AB12CD").orElseThrow().mode());

            assertTrue(repository.stopSearchRequest("AB12CD", 3_000L));
            assertTrue(repository.startSearchRequest(take));
            assertEquals(ItemSearchMode.TAKE,
                repository.getSearchRequest("AB12CD").orElseThrow().mode());
        }
    }

    @Test
    void expiredRequestsArePersistedAsExpiredAndExcludedFromActiveList() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(tempDir.resolve("expiry.db"))) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.startSearchRequest(request("AB12CD", ItemSearchMode.FIND, 1_000L, 2_000L));
            repository.startSearchRequest(request("EF34GH", ItemSearchMode.TAKE, 1_000L, 9_000L));

            assertEquals(1, repository.listActiveSearchRequests(3_000L, 0, 20).size());
            assertEquals(ItemSearchState.EXPIRED,
                repository.getSearchRequest("AB12CD").orElseThrow().state());
            assertEquals("EF34GH",
                repository.listActiveSearchRequests(3_000L, 0, 20).getFirst().code());
        }
    }

    @Test
    void removeClearAndRestartPreserveExpectedState() {
        Path database = tempDir.resolve("restart-search.db");
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(database)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.startSearchRequest(request("AB12CD", ItemSearchMode.FIND, 1_000L, 9_000L));
            repository.startSearchRequest(request("EF34GH", ItemSearchMode.TAKE, 1_000L, 9_000L));
            assertTrue(repository.removeSearchRequest("AB12CD"));
            assertEquals(1, repository.clearSearchRequests());
        }

        try (SqliteConnectionOwner reopened = new SqliteConnectionOwner(database)) {
            ItemSqliteRepository repository = new ItemSqliteRepository(reopened);
            assertTrue(repository.getSearchRequest("AB12CD").isEmpty());
            assertTrue(repository.getSearchRequest("EF34GH").isEmpty());
        }
    }

    @Test
    void observationMarksActiveRequestFoundWithoutMutatingItem() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("found-search.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.startSearchRequest(request(
                "AB12CD", ItemSearchMode.FIND, 1_000L, 9_000L
            ));

            assertTrue(repository.markSearchRequestFound("AB12CD", 2_000L));
            assertFalse(repository.markSearchRequestFound("AB12CD", 2_001L));
            assertEquals(
                ItemSearchState.FOUND,
                repository.getSearchRequest("AB12CD").orElseThrow().state()
            );
            assertTrue(repository.listActiveSearchRequests(2_000L, 0, 20).isEmpty());
        }
    }

    @Test
    void persistedObservationTransitionsMatchingRequestToFound() {
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("observation-found.db")
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            repository.startSearchRequest(request(
                "AB12CD", ItemSearchMode.TAKE, 1_000L, 9_000L
            ));

            repository.recordObservation(new ItemObservation(
                UUID.randomUUID(),
                "AB12CD",
                77L,
                new ObservationKey(HolderType.PLAYER, "player-a", 5),
                2_000L
            ));
            owner.flush();

            assertEquals(
                ItemSearchState.FOUND,
                repository.getSearchRequest("AB12CD").orElseThrow().state()
            );
        }
    }

    private ItemSearchRequest request(
        String code,
        ItemSearchMode mode,
        long createdAt,
        long expiresAt
    ) {
        return new ItemSearchRequest(
            code,
            mode,
            ItemSearchState.ACTIVE,
            UUID.randomUUID(),
            "Admin",
            createdAt,
            expiresAt,
            createdAt
        );
    }
}
