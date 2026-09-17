package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TagPublicationPolicyTest {

    private final TagPublicationPolicy policy = new TagPublicationPolicy();

    @Test
    void durableFailureNeverPublishes() {
        assertEquals(
            TagPublicationAction.NOOP,
            policy.afterPersistence(false, true, true)
        );
    }

    @Test
    void exactSourcePublishesOnlyAfterDurableSuccess() {
        assertEquals(
            TagPublicationAction.PUBLISH,
            policy.afterPersistence(true, true, true)
        );
    }

    @Test
    void changedSourceAbortsDurableReservation() {
        assertEquals(
            TagPublicationAction.ABORT,
            policy.afterPersistence(true, false, true)
        );
    }

    @Test
    void disabledPluginRetainsPreparedReservationWithoutPublishing() {
        assertEquals(
            TagPublicationAction.RETAIN_PREPARED,
            policy.afterPersistence(true, true, false)
        );
    }
}
