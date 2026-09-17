package com.itemguard.gui;

import java.util.UUID;

public final class HistoryNavigationPolicy {

    public HistoryNavigationAction exitAction(UUID parentPlayerUuid) {
        return parentPlayerUuid == null
            ? HistoryNavigationAction.CLOSE
            : HistoryNavigationAction.BACK_TO_PLAYER;
    }
}
