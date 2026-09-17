package com.itemguard.listeners;

public final class PlayerLifecyclePolicy {

    public PlayerLifecycleAction resolve(PlayerLifecycleEvent event) {
        return switch (event) {
            case DEATH -> PlayerLifecycleAction.LOG_DEATH_INVENTORY;
            case QUIT -> PlayerLifecycleAction.RELEASE_CACHE;
        };
    }
}
