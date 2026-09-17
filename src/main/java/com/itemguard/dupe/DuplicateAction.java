package com.itemguard.dupe;

public enum DuplicateAction {
    NOTIFY,
    REMOVE_NEWER,
    REMOVE_OLDER,
    REMOVE_ALL;

    public boolean isDestructive() {
        return this != NOTIFY;
    }
}
