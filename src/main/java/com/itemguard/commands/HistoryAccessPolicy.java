package com.itemguard.commands;

import java.util.Objects;
import java.util.UUID;
import java.util.List;

public final class HistoryAccessPolicy {

    public boolean canViewPlayer(UUID actorUuid, UUID targetUuid, boolean canViewOthers) {
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(targetUuid, "targetUuid");
        return actorUuid.equals(targetUuid) || canViewOthers;
    }

    public boolean canViewCode(boolean canViewOthers) {
        return canViewOthers;
    }

    public List<String> completionCandidates(
        String actorName,
        List<String> onlineNames,
        boolean canViewOthers
    ) {
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(onlineNames, "onlineNames");
        return canViewOthers ? List.copyOf(onlineNames) : List.of(actorName);
    }
}
