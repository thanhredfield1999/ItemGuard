package com.itemguard.listeners;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerLifecyclePolicyTest {

    private final PlayerLifecyclePolicy policy = new PlayerLifecyclePolicy();

    @Test
    void deathLogsInventoryHistory() {
        assertEquals(
            PlayerLifecycleAction.LOG_DEATH_INVENTORY,
            policy.resolve(PlayerLifecycleEvent.DEATH)
        );
    }

    @Test
    void quitOnlyReleasesRuntimeCache() {
        assertEquals(
            PlayerLifecycleAction.RELEASE_CACHE,
            policy.resolve(PlayerLifecycleEvent.QUIT)
        );
    }
}
