package com.itemguard.commands;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryAccessPolicyTest {

    private final HistoryAccessPolicy policy = new HistoryAccessPolicy();

    @Test
    void memberCanViewOwnHistoryButNotAnotherPlayer() {
        UUID actor = UUID.randomUUID();

        assertTrue(policy.canViewPlayer(actor, actor, false));
        assertFalse(policy.canViewPlayer(actor, UUID.randomUUID(), false));
    }

    @Test
    void staffCapabilityAllowsOtherPlayersAndDirectCodes() {
        assertTrue(policy.canViewPlayer(UUID.randomUUID(), UUID.randomUUID(), true));
        assertTrue(policy.canViewCode(true));
        assertFalse(policy.canViewCode(false));
    }

    @Test
    void completionOnlyExposesSelfToMembers() {
        assertTrue(policy.completionCandidates("Thanh", List.of("Thanh", "Other"), false)
            .equals(List.of("Thanh")));
        assertTrue(policy.completionCandidates("Thanh", List.of("Thanh", "Other"), true)
            .equals(List.of("Thanh", "Other")));
    }
}
