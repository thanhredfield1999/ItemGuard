package com.itemguard.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H1 (review 2026-09-16): a pending filter query had no expiry.
 *
 * <p>An admin who pressed Filter and then went to do something else kept the request armed until
 * they quit, so their next chat message — possibly hours later, mid-conversation — was cancelled
 * and interpreted as a filter query. Their message disappeared from chat and they got a private
 * item list instead. The code read correctly; what it did to the player was the defect.
 */
class FilterChatListenerExpiryTest {

    @Test
    void aFreshRequestIsStillAnswered() {
        assertFalse(FilterChatListener.expired(1_000L, 1_000L, 30_000L));
        assertFalse(FilterChatListener.expired(1_000L, 30_999L, 30_000L));
    }

    @Test
    void aRequestLeftArmedLongEnoughToGoStaleIsNotAnswered() {
        assertTrue(FilterChatListener.expired(1_000L, 31_000L, 30_000L));
        // The reported shape of the bug: hours later, in the middle of a conversation.
        assertTrue(FilterChatListener.expired(1_000L, 1_000L + 3 * 60 * 60 * 1_000L, 30_000L));
    }

    @Test
    void theWindowIsTheOneThePlayerWasTold() {
        assertTrue(FilterChatListener.FILTER_INPUT_TTL_MS > 0L);
        assertTrue(
            FilterChatListener.expired(0L, FilterChatListener.FILTER_INPUT_TTL_MS, FilterChatListener.FILTER_INPUT_TTL_MS),
            "the expiry must actually fire at the advertised window"
        );
    }
}
