package com.itemguard.dupe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AntiDupeActionPolicyTest {

    private final AntiDupeActionPolicy policy = new AntiDupeActionPolicy();

    @Test
    void destructiveActionIsDowngradedWhenReleaseGateIsClosed() {
        assertEquals(DuplicateAction.NOTIFY, policy.resolve("REMOVE_ALL", false));
        assertEquals(DuplicateAction.NOTIFY, policy.resolve("REMOVE_NEWER", false));
        assertEquals(DuplicateAction.NOTIFY, policy.resolve("REMOVE_OLDER", false));
    }

    @Test
    void notifyRemainsAvailableWhenReleaseGateIsClosed() {
        assertEquals(DuplicateAction.NOTIFY, policy.resolve("NOTIFY", false));
    }

    @Test
    void unknownOrMissingActionFailsClosedToNotify() {
        assertEquals(DuplicateAction.NOTIFY, policy.resolve("DELETE_EVERYTHING", true));
        assertEquals(DuplicateAction.NOTIFY, policy.resolve(null, true));
    }

    @Test
    void destructiveActionRequiresExplicitReleaseGate() {
        assertEquals(DuplicateAction.REMOVE_ALL, policy.resolve("REMOVE_ALL", true));
    }
}
