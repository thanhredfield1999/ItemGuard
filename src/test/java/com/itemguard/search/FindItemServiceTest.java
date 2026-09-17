package com.itemguard.search;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindItemServiceTest {

    @Test
    void startsTrackedFindRequestWithBoundedExpiry() {
        FakeStore store = new FakeStore();
        FindItemService service = new FindItemService(store, code -> code.equals("AB12CD"), () -> 1_000L);

        FindItemStartResult result = service.start(
            "ab12cd",
            ItemSearchMode.FIND,
            Duration.ofMinutes(5),
            UUID.randomUUID(),
            "Admin"
        );

        assertEquals(FindItemStartResult.STARTED, result);
        ItemSearchRequest request = store.getSearchRequest("AB12CD").orElseThrow();
        assertEquals(ItemSearchMode.FIND, request.mode());
        assertEquals(301_000L, request.expiresAt());
    }

    @Test
    void rejectsUnknownOrAlreadyActiveItem() {
        FakeStore store = new FakeStore();
        FindItemService service = new FindItemService(store, code -> code.equals("AB12CD"), () -> 1_000L);

        assertEquals(FindItemStartResult.NOT_TRACKED, service.start(
            "MISSING", ItemSearchMode.FIND, Duration.ofMinutes(1), null, "Console"));
        assertEquals(FindItemStartResult.STARTED, service.start(
            "AB12CD", ItemSearchMode.TAKE, Duration.ofMinutes(1), null, "Console"));
        assertEquals(FindItemStartResult.ALREADY_ACTIVE, service.start(
            "AB12CD", ItemSearchMode.FIND, Duration.ofMinutes(2), null, "Console"));
    }

    @Test
    void stopListRemoveAndClearDelegateThroughServiceBoundary() {
        FakeStore store = new FakeStore();
        FindItemService service = new FindItemService(
            store,
            code -> true,
            () -> 1_000L
        );
        service.start("AB12CD", ItemSearchMode.FIND, Duration.ofMinutes(1), null, "Console");
        service.start("EF34GH", ItemSearchMode.TAKE, Duration.ofMinutes(1), null, "Console");

        assertEquals(2, service.listActive(0, 20).size());
        assertTrue(service.stop("AB12CD"));
        assertFalse(service.stop("AB12CD"));
        assertTrue(service.remove("AB12CD"));
        assertEquals(1, service.clear());
        assertTrue(service.listActive(0, 20).isEmpty());
    }

    private static final class FakeStore implements SearchRequestStore {
        private final Map<String, ItemSearchRequest> requests = new HashMap<>();

        @Override
        public boolean startSearchRequest(ItemSearchRequest request) {
            ItemSearchRequest existing = requests.get(request.code());
            if (existing != null && existing.state() == ItemSearchState.ACTIVE) {
                return false;
            }
            requests.put(request.code(), request);
            return true;
        }

        @Override
        public boolean stopSearchRequest(String code, long updatedAt) {
            ItemSearchRequest existing = requests.get(code);
            if (existing == null || existing.state() != ItemSearchState.ACTIVE) {
                return false;
            }
            requests.put(code, new ItemSearchRequest(
                existing.code(),
                existing.mode(),
                ItemSearchState.STOPPED,
                existing.actorUuid(),
                existing.actorName(),
                existing.createdAt(),
                existing.expiresAt(),
                updatedAt
            ));
            return true;
        }

        @Override
        public Optional<ItemSearchRequest> getSearchRequest(String code) {
            return Optional.ofNullable(requests.get(code));
        }

        @Override
        public List<ItemSearchRequest> listActiveSearchRequests(long now, int offset, int limit) {
            return requests.values().stream()
                .filter(request -> request.state() == ItemSearchState.ACTIVE)
                .skip(offset)
                .limit(limit)
                .toList();
        }

        @Override
        public boolean removeSearchRequest(String code) {
            return requests.remove(code) != null;
        }

        @Override
        public int clearSearchRequests() {
            int size = requests.size();
            requests.clear();
            return size;
        }
    }
}
