package com.itemguard.reclaim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protocol that finally hands an item back.
 *
 * <p>The claim table already refuses a second active claim per identity
 * (`idx_reclaim_identity_lock` covers PENDING, PREPARED and COMMITTED), so the interesting question is
 * not "does the plugin check" but "in what order do the record and the physical hand-over happen, and
 * what does each failure leave behind". This service owns the two state transitions either side of the
 * hand-over; the command owns the inventory write between them.
 *
 * <p>The rule that matters most: a delivery that <em>happened</em> may never end in a state that
 * allows another claim (that is a duplication path), and a delivery that did <em>not</em> happen must
 * end in a state that does allow a retry (otherwise the player's item is lost by a full inventory).
 */
class ReclaimIssuanceServiceTest {

    private static final UUID CLAIM_ID = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
    private static final UUID PLAYER = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");

    /** Records every transition the service asks for, and can be told to lose the race. */
    private static final class RecordingStore implements ReclaimClaimStore {
        private final List<String> calls = new ArrayList<>();
        private boolean transitionSucceeds = true;

        @Override
        public boolean beginReclaimClaim(ReclaimClaim claim) {
            calls.add("begin " + claim.state());
            return true;
        }

        @Override
        public boolean transitionReclaimClaim(
            UUID claimId,
            ReclaimClaimState expectedState,
            ReclaimClaimState targetState,
            long updatedAt,
            String detail
        ) {
            calls.add(expectedState + "->" + targetState + " detail=" + detail + " at=" + updatedAt);
            return transitionSucceeds;
        }

        @Override
        public Optional<ReclaimClaim> getReclaimClaim(UUID claimId) {
            return Optional.empty();
        }
    }

    private static ReclaimClaim claim(ReclaimClaimState state) {
        return new ReclaimClaim(CLAIM_ID, "key-" + CLAIM_ID, PLAYER, "AB12CD", state, 1_000L, 1_000L, null);
    }

    private static ReclaimIssuanceService service(
        ReclaimClaimStore store,
        boolean enabled,
        AtomicLong clock
    ) {
        return new ReclaimIssuanceService(store, () -> enabled, clock::get);
    }

    @Test
    void aDisabledGateArmsNothingAndWritesNothing() {
        RecordingStore store = new RecordingStore();
        AtomicLong clock = new AtomicLong(5_000L);

        ReclaimIssuanceDecision decision =
            service(store, false, clock).arm(claim(ReclaimClaimState.PENDING));

        assertEquals(ReclaimIssuanceStatus.REFUSED_DISABLED, decision.status());
        assertTrue(store.calls.isEmpty(),
            "a refused arming must not touch the claim table: the operator turned issuance off");
    }

    @Test
    void armingMovesAReservedClaimToPrepared() {
        RecordingStore store = new RecordingStore();
        AtomicLong clock = new AtomicLong(5_000L);

        ReclaimIssuanceDecision decision =
            service(store, true, clock).arm(claim(ReclaimClaimState.PENDING));

        assertEquals(ReclaimIssuanceStatus.ARMED, decision.status());
        assertEquals(List.of("PENDING->PREPARED detail=armed for delivery at=5000"), store.calls);
    }

    @Test
    void armingRefusesWhenTheClaimIsNotReserved() {
        for (ReclaimClaimState state : List.of(
            ReclaimClaimState.PREPARED, ReclaimClaimState.COMMITTED, ReclaimClaimState.DENIED
        )) {
            RecordingStore store = new RecordingStore();
            ReclaimIssuanceDecision decision =
                service(store, true, new AtomicLong(5_000L)).arm(claim(state));

            assertEquals(ReclaimIssuanceStatus.REFUSED_STATE, decision.status(), "state " + state);
            assertTrue(store.calls.isEmpty(), "no transition may be attempted from " + state);
        }
    }

    @Test
    void aLostRaceIsReportedRatherThanAssumed() {
        RecordingStore store = new RecordingStore();
        store.transitionSucceeds = false;

        ReclaimIssuanceDecision decision =
            service(store, true, new AtomicLong(5_000L)).arm(claim(ReclaimClaimState.PENDING));

        assertEquals(ReclaimIssuanceStatus.REFUSED_STALE, decision.status(),
            "a transition that did not apply must never be reported as done");
    }

    @Test
    void aDeliveredItemCommitsAndCanNeverBeClaimedAgain() {
        RecordingStore store = new RecordingStore();
        AtomicLong clock = new AtomicLong(7_500L);

        ReclaimIssuanceDecision decision = service(store, true, clock).settle(
            claim(ReclaimClaimState.PREPARED),
            true,
            "ThanhRedfield",
            "delivered to player inventory"
        );

        assertEquals(ReclaimIssuanceStatus.ISSUED, decision.status());
        assertEquals(1, store.calls.size());
        String call = store.calls.get(0);
        assertTrue(call.startsWith("PREPARED->COMMITTED"), call);
        assertTrue(call.contains("at=7500"), call);
        assertTrue(call.contains("ThanhRedfield"), call);
        assertFalse(call.contains("DENIED"),
            "a delivered item must never be recorded as denied — that state allows another claim");
    }

    @Test
    void aDeliveryThatDidNotHappenStaysRetryable() {
        RecordingStore store = new RecordingStore();

        ReclaimIssuanceDecision decision = service(store, true, new AtomicLong(7_500L)).settle(
            claim(ReclaimClaimState.PREPARED),
            false,
            "ThanhRedfield",
            "inventory full"
        );

        assertEquals(ReclaimIssuanceStatus.ABORTED_RETRYABLE, decision.status());
        assertEquals(1, store.calls.size());
        assertTrue(store.calls.get(0).startsWith("PREPARED->DENIED"), store.calls.get(0));
        assertTrue(store.calls.get(0).contains("inventory full"), store.calls.get(0));
    }

    @Test
    void settlingIsRefusedFromAnyStateButPrepared() {
        for (ReclaimClaimState state : List.of(
            ReclaimClaimState.PENDING, ReclaimClaimState.COMMITTED, ReclaimClaimState.DENIED
        )) {
            RecordingStore store = new RecordingStore();
            ReclaimIssuanceDecision decision = service(store, true, new AtomicLong(1L))
                .settle(claim(state), true, "ThanhRedfield", "delivered");

            assertEquals(ReclaimIssuanceStatus.REFUSED_STATE, decision.status(), "state " + state);
            assertTrue(store.calls.isEmpty(),
                "settling from " + state + " would issue an item without an armed claim");
        }
    }

    @Test
    void armingHandsBackTheClaimInItsPreparedStateSoTheFlowCanSettleIt() {
        RecordingStore store = new RecordingStore();

        ReclaimIssuanceDecision armed = service(store, true, new AtomicLong(5_000L))
            .arm(claim(ReclaimClaimState.PENDING));

        // The runtime gate found this the hard way (2026-09-19): the flow armed a claim and then
        // settled with the record it already had, whose state still said PENDING, so settle refused
        // every time and the identity stayed locked in PREPARED. Armed must carry the moved copy.
        assertEquals(ReclaimClaimState.PREPARED, armed.claim().state(),
            "the armed decision must carry the claim after the transition, not before it");

        ReclaimIssuanceDecision settled = service(store, true, new AtomicLong(5_100L))
            .settle(armed.claim(), true, "ThanhRedfield", "delivered to player inventory");
        assertEquals(ReclaimIssuanceStatus.ISSUED, settled.status(),
            "settling with the claim the arm step returned must commit the delivery");
    }

    @Test
    void aLostArmKeepsTheOriginalClaimBecauseNothingWasApplied() {
        RecordingStore store = new RecordingStore();
        store.transitionSucceeds = false;

        ReclaimIssuanceDecision armed = service(store, true, new AtomicLong(5_000L))
            .arm(claim(ReclaimClaimState.PENDING));

        assertEquals(ReclaimIssuanceStatus.REFUSED_STALE, armed.status());
        assertEquals(ReclaimClaimState.PENDING, armed.claim().state(),
            "a transition that did not apply must not be reported as applied to the caller");
    }

    @Test
    void aRefusedArmDoesNotMoveTheClaimEither() {
        RecordingStore store = new RecordingStore();

        ReclaimIssuanceDecision armed = service(store, false, new AtomicLong(5_000L))
            .arm(claim(ReclaimClaimState.PENDING));

        assertEquals(ReclaimIssuanceStatus.REFUSED_DISABLED, armed.status());
        assertEquals(ReclaimClaimState.PENDING, armed.claim().state());
    }

    @Test
    void recordingADeliverySurvivesTheGateBeingSwitchedOffMidFlight() {
        RecordingStore store = new RecordingStore();

        // The item already left the plugin's hands because the gate was on when it was armed.
        // Refusing to record that would leave a delivered item with a claim that allows another one.
        ReclaimIssuanceDecision decision = service(store, false, new AtomicLong(9_000L)).settle(
            claim(ReclaimClaimState.PREPARED),
            true,
            "ThanhRedfield",
            "delivered before the operator disabled the gate"
        );

        assertEquals(ReclaimIssuanceStatus.ISSUED, decision.status());
        assertEquals(1, store.calls.size(), "the record of a real hand-over must still be written");
    }

    @Test
    void theDetailIsBoundedSoAFailureMessageCannotBlowUpTheRow() {
        RecordingStore store = new RecordingStore();

        service(store, true, new AtomicLong(1L)).settle(
            claim(ReclaimClaimState.PREPARED),
            false,
            "ThanhRedfield",
            "x".repeat(1_000)
        );

        String recorded = store.calls.get(0);
        int detailStart = recorded.indexOf("detail=") + "detail=".length();
        String detail = recorded.substring(detailStart, recorded.indexOf(" at="));
        assertTrue(detail.length() <= 256, "detail length was " + detail.length());
        assertNotNull(detail);
    }
}
