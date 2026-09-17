package com.itemguard.custody;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The anti-farm window must be a custody-specific setting.
 *
 * <p>The duplicate-detection cooldown defaults to five seconds, which is shorter than a single
 * deliberate hand-over between two players, so reusing it as the custody window would let two
 * colluding accounts raise the count at will. Custody therefore has its own, much longer default.
 */
class CustodyWindowTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Test void theDefaultWindowIsLongEnoughToOutlastADeliberateSwap() {
        assertTrue(CustodyWindow.DEFAULT_MILLIS >= 5 * 60_000L,
            "a few seconds is shorter than one hand-over round trip, so it would not throttle anything");
    }

    @Test void aRealisticBotStyleSwapLoopCannotFarmTheCountUnderTheDefault() {
        CustodyTransferPolicy policy = new CustodyTransferPolicy(CustodyWindow.DEFAULT_MILLIS);
        CustodyState state = policy.observe(CustodyState.empty(), A, 0L).state();
        long now = 0L;
        // Each swap takes about eight seconds, which is faster than two players can do it by hand.
        for (int i = 0; i < 30; i++) {
            now += 8_000L;
            state = policy.observe(state, B, now).state();
            now += 8_000L;
            state = policy.observe(state, A, now).state();
        }
        assertEquals(1, state.transfers(), "sustained swapping must not inflate the count");
        assertEquals(2, state.distinctHolders());
    }

    @Test void configuredValuesAreAcceptedAndClampedToZero() {
        assertEquals(60_000L, CustodyWindow.resolve(60_000L));
        assertEquals(0L, CustodyWindow.resolve(-1L), "a negative window disables throttling, not crashes");
    }

    @Test void anUnsetValueFallsBackToTheDefaultRatherThanToZero() {
        assertEquals(CustodyWindow.DEFAULT_MILLIS, CustodyWindow.resolve(null));
    }
}
