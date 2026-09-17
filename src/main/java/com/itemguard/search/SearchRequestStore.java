package com.itemguard.search;

import java.util.List;
import java.util.Optional;

public interface SearchRequestStore {
    boolean startSearchRequest(ItemSearchRequest request);
    boolean stopSearchRequest(String code, long updatedAt);
    Optional<ItemSearchRequest> getSearchRequest(String code);
    List<ItemSearchRequest> listActiveSearchRequests(long now, int offset, int limit);
    boolean removeSearchRequest(String code);
    int clearSearchRequests();
}
