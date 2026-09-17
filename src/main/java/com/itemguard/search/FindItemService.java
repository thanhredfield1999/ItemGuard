package com.itemguard.search;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class FindItemService {

    private final SearchRequestStore store;
    private final TrackedItemLookup trackedItems;
    private final LongSupplier clock;

    public FindItemService(
        SearchRequestStore store,
        TrackedItemLookup trackedItems,
        LongSupplier clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.trackedItems = Objects.requireNonNull(trackedItems, "trackedItems");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public FindItemStartResult start(
        String code,
        ItemSearchMode mode,
        Duration duration,
        UUID actorUuid,
        String actorName
    ) {
        ItemSearchRequest request = createRequest(
            code, mode, duration, actorUuid, actorName
        );
        if (!trackedItems.exists(request.code())) {
            return FindItemStartResult.NOT_TRACKED;
        }
        return store.startSearchRequest(request)
            ? FindItemStartResult.STARTED
            : FindItemStartResult.ALREADY_ACTIVE;
    }

    public boolean stop(String code) {
        return store.stopSearchRequest(code, clock.getAsLong());
    }

    public List<ItemSearchRequest> listActive(int offset, int limit) {
        return store.listActiveSearchRequests(clock.getAsLong(), offset, limit);
    }

    public boolean remove(String code) {
        return store.removeSearchRequest(code);
    }

    public int clear() {
        return store.clearSearchRequests();
    }

    private ItemSearchRequest createRequest(
        String code,
        ItemSearchMode mode,
        Duration duration,
        UUID actorUuid,
        String actorName
    ) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Search duration must be positive");
        }
        long now = clock.getAsLong();
        long expiresAt = Math.addExact(now, duration.toMillis());
        return new ItemSearchRequest(
            code,
            mode,
            ItemSearchState.ACTIVE,
            actorUuid,
            actorName,
            now,
            expiresAt,
            now
        );
    }
}
