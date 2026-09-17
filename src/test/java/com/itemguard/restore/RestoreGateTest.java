package com.itemguard.restore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gate that decides whether an identity may be handed back to a player.
 *
 * <p>Specification: {@code docs/design/2026-09-12-loss-and-restore.md}. Restoring items is the
 * easiest way to introduce duplication into an anti-duplication plugin, so every refusal reason is
 * tested explicitly and the default for anything unrecognised is refusal.
 */
class RestoreGateTest {

    private static final long EPOCH = 500L;

    private static RestoreRequest request() {
        return new RestoreRequest("11GXH7", IdentityState.DESTROYED, false, EPOCH, EPOCH, false, true);
    }

    @Test void aDestroyedIdentityConfirmedAbsentAndNeverRestoredIsAllowed() {
        RestoreDecision decision = RestoreGate.evaluate(request());
        assertTrue(decision.allowed());
        assertEquals(RestoreRefusal.NONE, decision.refusal());
    }

    @Test void anIdentityThatStillExistsIsRefusedBecauseRestoringItWouldDuplicateIt() {
        RestoreDecision decision = RestoreGate.evaluate(
            new RestoreRequest("11GXH7", IdentityState.HELD, false, EPOCH, EPOCH, false, true));
        assertFalse(decision.allowed());
        assertEquals(RestoreRefusal.NOT_DESTROYED, decision.refusal());
    }

    @Test void restoringTwiceIsRefusedForeverEvenIfTheItemIsDestroyedAgain() {
        RestoreDecision decision = RestoreGate.evaluate(
            new RestoreRequest("11GXH7", IdentityState.DESTROYED, true, EPOCH, EPOCH, false, true));
        assertFalse(decision.allowed());
        assertEquals(RestoreRefusal.ALREADY_RESTORED, decision.refusal());
    }

    @Test void presenceInTheConfirmingEpochIsRefusedBecauseTheItemWasSeenAlive() {
        RestoreDecision decision = RestoreGate.evaluate(
            new RestoreRequest("11GXH7", IdentityState.DESTROYED, false, EPOCH, EPOCH, true, true));
        assertFalse(decision.allowed());
        assertEquals(RestoreRefusal.SEEN_IN_LATEST_EPOCH, decision.refusal());
    }

    @Test void aStaleConfirmationIsRefusedSoLossMustBeReconfirmed() {
        // The loss was confirmed by epoch 400, but the world has been swept again since.
        RestoreDecision decision = RestoreGate.evaluate(
            new RestoreRequest("11GXH7", IdentityState.DESTROYED, false, 400L, EPOCH, false, true));
        assertFalse(decision.allowed());
        assertEquals(RestoreRefusal.STALE_CONFIRMATION, decision.refusal());
    }

    @Test void missingPermissionIsRefused() {
        RestoreDecision decision = RestoreGate.evaluate(
            new RestoreRequest("11GXH7", IdentityState.DESTROYED, false, EPOCH, EPOCH, false, false));
        assertFalse(decision.allowed());
        assertEquals(RestoreRefusal.NO_PERMISSION, decision.refusal());
    }

    @Test void anUnknownIdentityIsRefusedRatherThanInvented() {
        RestoreDecision decision = RestoreGate.evaluate(
            new RestoreRequest("NOPE12", IdentityState.UNKNOWN, false, EPOCH, EPOCH, false, true));
        assertFalse(decision.allowed());
        assertEquals(RestoreRefusal.UNKNOWN_IDENTITY, decision.refusal());
    }

    @Test void anIdentityNeverConfirmedLostIsRefused() {
        // Never absent from any completed epoch: state may say destroyed, but nothing proved it.
        RestoreDecision decision = RestoreGate.evaluate(
            new RestoreRequest("11GXH7", IdentityState.DESTROYED, false, 0L, EPOCH, false, true));
        assertFalse(decision.allowed());
        assertEquals(RestoreRefusal.NOT_CONFIRMED_LOST, decision.refusal());
    }

    @Test void aNullRequestIsRefusedRatherThanThrowing() {
        RestoreDecision decision = RestoreGate.evaluate(null);
        assertFalse(decision.allowed());
        assertEquals(RestoreRefusal.UNKNOWN_IDENTITY, decision.refusal());
    }

    @Test void everyRefusalCarriesAnOperatorReadableReason() {
        for (RestoreRefusal refusal : RestoreRefusal.values()) {
            assertNotNull(refusal.message());
            assertFalse(refusal.message().isBlank(), refusal.name() + " needs a message");
        }
    }

    @Test void permissionIsCheckedBeforeTheItemStateSoAdminsLearnTheRealBlocker() {
        // A non-admin must not be able to probe identity states through refusal messages.
        RestoreDecision decision = RestoreGate.evaluate(
            new RestoreRequest("11GXH7", IdentityState.HELD, true, 0L, EPOCH, true, false));
        assertEquals(RestoreRefusal.NO_PERMISSION, decision.refusal());
    }
}
