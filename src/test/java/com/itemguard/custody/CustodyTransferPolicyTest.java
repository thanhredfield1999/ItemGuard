package com.itemguard.custody;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Custody counting rules.
 *
 * <p>The product question is "how many times did this item really change hands", not "how many
 * events did the server record". Throwing an item on the floor and picking it back up is the same
 * person holding it, so it must not count. Handing it to another player must count, and the item
 * must remember that the previous player once held it.
 */
class CustodyTransferPolicyTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    private static final long MINUTE = 60_000L;

    private final CustodyTransferPolicy policy = new CustodyTransferPolicy(5 * MINUTE);

    @Test void firstObservedHolderStartsTheChainAndCountsAsTheOriginNotATransfer() {
        CustodyState state = CustodyState.empty();
        CustodyDecision decision = policy.observe(state, A, 0L);
        assertEquals(CustodyOutcome.FIRST_HOLDER, decision.outcome());
        CustodyState next = decision.state();
        assertEquals(A, next.holder());
        assertEquals(0, next.transfers(), "becoming the first holder is not a transfer");
        assertEquals(List.of(A), next.knownHolders());
    }

    @Test void theSamePlayerDroppingAndPickingUpDoesNotCount() {
        CustodyState state = policy.observe(CustodyState.empty(), A, 0L).state();
        for (int i = 1; i <= 50; i++) {
            CustodyDecision decision = policy.observe(state, A, i * MINUTE);
            assertEquals(CustodyOutcome.SAME_HOLDER, decision.outcome(),
                "re-picking your own item is never a transfer");
            state = decision.state();
        }
        assertEquals(0, state.transfers(), "self drop/pickup spam must not inflate the count");
        assertEquals(List.of(A), state.knownHolders());
        assertEquals(A, state.holder());
    }

    @Test void handingTheItemToAnotherPlayerCountsOnceAndRemembersBothHolders() {
        CustodyState state = policy.observe(CustodyState.empty(), A, 0L).state();
        CustodyDecision decision = policy.observe(state, B, MINUTE);
        assertEquals(CustodyOutcome.TRANSFER, decision.outcome());
        CustodyState next = decision.state();
        assertEquals(B, next.holder());
        assertEquals(1, next.transfers());
        assertEquals(List.of(A, B), next.knownHolders(), "the chain records who held it, in order");
    }

    @Test void twoPlayersPassingItBackAndForthWithinTheCooldownMoveCustodyButDoNotInflateTheCount() {
        CustodyState state = policy.observe(CustodyState.empty(), A, 0L).state();
        state = policy.observe(state, B, MINUTE).state();
        assertEquals(1, state.transfers());

        // B hands it straight back, then they keep swapping inside the cooldown window.
        long now = MINUTE;
        for (int i = 0; i < 20; i++) {
            now += 5_000L;
            CustodyDecision back = policy.observe(state, A, now);
            assertEquals(CustodyOutcome.TRANSFER_THROTTLED, back.outcome());
            state = back.state();
            assertEquals(A, state.holder(), "custody must stay accurate even when the count is throttled");

            now += 5_000L;
            CustodyDecision forth = policy.observe(state, B, now);
            assertEquals(CustodyOutcome.TRANSFER_THROTTLED, forth.outcome());
            state = forth.state();
            assertEquals(B, state.holder());
        }
        assertEquals(1, state.transfers(), "ping-pong between the same pair must not farm the count");
        assertEquals(List.of(A, B), state.knownHolders());
    }

    @Test void theSamePairCountsAgainOnceTheCooldownHasPassed() {
        CustodyState state = policy.observe(CustodyState.empty(), A, 0L).state();
        state = policy.observe(state, B, MINUTE).state();
        state = policy.observe(state, A, MINUTE + 1_000L).state();
        assertEquals(1, state.transfers());

        CustodyDecision later = policy.observe(state, B, MINUTE + 1_000L + 5 * MINUTE);
        assertEquals(CustodyOutcome.TRANSFER, later.outcome(),
            "a genuine handover long after the last one is real movement, not spam");
        assertEquals(2, later.state().transfers());
    }

    @Test void aThirdPlayerAlwaysCountsEvenImmediatelyBecauseItIsNotThePairBeingThrottled() {
        CustodyState state = policy.observe(CustodyState.empty(), A, 0L).state();
        state = policy.observe(state, B, MINUTE).state();
        CustodyDecision decision = policy.observe(state, C, MINUTE + 1_000L);
        assertEquals(CustodyOutcome.TRANSFER, decision.outcome());
        assertEquals(2, decision.state().transfers());
        assertEquals(List.of(A, B, C), decision.state().knownHolders());
    }

    @Test void returningToAnEarlierHolderDoesNotDuplicateThemInTheChain() {
        CustodyState state = policy.observe(CustodyState.empty(), A, 0L).state();
        state = policy.observe(state, B, MINUTE).state();
        state = policy.observe(state, C, 2 * MINUTE).state();
        state = policy.observe(state, A, 20 * MINUTE).state();
        assertEquals(List.of(A, B, C), state.knownHolders(), "the chain lists distinct holders");
        assertEquals(3, state.distinctHolders());
        assertEquals(A, state.holder());
    }

    @Test void distinctHolderCountCannotBeFarmedByTwoAccounts() {
        CustodyState state = policy.observe(CustodyState.empty(), A, 0L).state();
        long now = 0L;
        for (int i = 0; i < 100; i++) {
            now += 10 * MINUTE;
            state = policy.observe(state, B, now).state();
            now += 10 * MINUTE;
            state = policy.observe(state, A, now).state();
        }
        assertEquals(2, state.distinctHolders(),
            "two colluding accounts can raise transfers but never the number of real holders");
        assertTrue(state.transfers() > 2, "genuine spaced handovers still register as movement");
    }

    @Test void aNullActorIsIgnoredSoAutomationNeverRewritesCustody() {
        CustodyState state = policy.observe(CustodyState.empty(), A, 0L).state();
        CustodyDecision decision = policy.observe(state, null, MINUTE);
        assertEquals(CustodyOutcome.NO_ACTOR, decision.outcome());
        assertSame(state, decision.state(), "an unattributed movement must leave custody untouched");
    }

    @Test void anOutOfOrderEventNeverRewindsCustody() {
        CustodyState state = policy.observe(CustodyState.empty(), A, 10 * MINUTE).state();
        CustodyDecision stale = policy.observe(state, B, 5 * MINUTE);
        assertEquals(CustodyOutcome.STALE_EVENT, stale.outcome());
        assertSame(state, stale.state());
        assertEquals(A, state.holder());
    }

    @Test void zeroCooldownMeansEveryGenuineHandoverCounts() {
        CustodyTransferPolicy immediate = new CustodyTransferPolicy(0L);
        CustodyState state = immediate.observe(CustodyState.empty(), A, 0L).state();
        state = immediate.observe(state, B, 1L).state();
        state = immediate.observe(state, A, 2L).state();
        assertEquals(2, state.transfers());
    }

    @Test void negativeCooldownIsRejectedRatherThanSilentlyDisablingTheGuard() {
        assertThrows(IllegalArgumentException.class, () -> new CustodyTransferPolicy(-1L));
    }
}
