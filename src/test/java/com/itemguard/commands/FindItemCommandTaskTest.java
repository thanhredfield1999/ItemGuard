package com.itemguard.commands;

import com.itemguard.search.FindItemService;
import com.itemguard.search.ItemSearchMode;
import com.itemguard.search.ItemSearchRequest;
import com.itemguard.search.ItemSearchState;
import com.itemguard.search.SearchRequestStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindItemCommandTaskTest {

    @Test
    void executesStartListStopAndClearWithoutBukkitState() {
        FakeStore store = new FakeStore();
        FindItemService service = new FindItemService(
            store,
            code -> code.equals("AB12CD"),
            () -> 1_000L
        );
        FindItemCommandTask task = new FindItemCommandTask(service, () -> 1_000L);
        UUID actorUuid = UUID.randomUUID();

        List<String> started = task.execute(
            new FindItemCommandAction.Start(
                "AB12CD",
                ItemSearchMode.TAKE,
                Duration.ofMinutes(5)
            ),
            actorUuid,
            "Admin"
        );
        List<String> listed = task.execute(
            new FindItemCommandAction.ListActive(1),
            actorUuid,
            "Admin"
        );
        List<String> stopped = task.execute(
            new FindItemCommandAction.Stop("AB12CD"),
            actorUuid,
            "Admin"
        );
        List<String> cleared = task.execute(
            FindItemCommandAction.ClearConfirmed.INSTANCE,
            actorUuid,
            "Admin"
        );

        assertEquals(2, started.size());
        assertTrue(started.get(0).contains("Da tao yeu cau"));
        assertTrue(started.get(0).contains("TAKE"));
        assertTrue(started.get(1).contains("intent"));
        assertTrue(listed.stream().anyMatch(message -> message.contains("AB12CD")));
        assertTrue(stopped.getFirst().contains("Da dung tim ID"));
        assertTrue(cleared.getFirst().contains("Da xoa"));
        assertTrue(cleared.getFirst().contains("1"));
    }

    @Test
    void returnsBoundedFailureMessagesForUnknownAndLargePage() {
        FindItemService service = new FindItemService(
            new FakeStore(),
            code -> false,
            () -> 1_000L
        );
        FindItemCommandTask task = new FindItemCommandTask(service, () -> 1_000L);

        List<String> unknown = task.execute(
            new FindItemCommandAction.Start(
                "MISSING",
                ItemSearchMode.FIND,
                Duration.ofSeconds(10)
            ),
            null,
            "Console"
        );
        List<String> overflow = task.execute(
            new FindItemCommandAction.ListActive(Integer.MAX_VALUE),
            null,
            "Console"
        );

        assertTrue(unknown.getFirst().contains("ID chua duoc theo doi"));
        assertTrue(overflow.getFirst().contains("So trang qua lon"));
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
        public List<ItemSearchRequest> listActiveSearchRequests(
            long now,
            int offset,
            int limit
        ) {
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
