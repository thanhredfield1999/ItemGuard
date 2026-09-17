package com.itemguard.gui;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryNavigationPolicyTest {

    private final HistoryNavigationPolicy policy = new HistoryNavigationPolicy();

    @Test
    void codeOnlyHistoryClosesInsteadOfDereferencingMissingParent() {
        assertEquals(HistoryNavigationAction.CLOSE, policy.exitAction(null));
    }

    @Test
    void playerHistoryReturnsToItsPlayerBrowser() {
        assertEquals(HistoryNavigationAction.BACK_TO_PLAYER,
            policy.exitAction(UUID.randomUUID()));
    }
}
