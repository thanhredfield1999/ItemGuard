package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftOutputPolicyTest {

    private final CraftOutputPolicy policy = new CraftOutputPolicy();

    @Test
    void eligibleUntaggedOutputIsCancelledUntilTransactionalCraftExists() {
        assertEquals(
            CraftOutputPolicy.Action.CANCEL_UNTAGGED_ELIGIBLE,
            policy.decide(false, false, true)
        );
    }

    @Test
    void taggedButNotReadyOutputFailsClosed() {
        assertEquals(
            CraftOutputPolicy.Action.CANCEL_TAGGED_NOT_READY,
            policy.decide(true, false, true)
        );
    }

    @Test
    void taggedReadyRecipeOutputIsCancelledToPreventIdentityCopying() {
        assertEquals(
            CraftOutputPolicy.Action.CANCEL_TAGGED_READY_COPY,
            policy.decide(true, true, true)
        );
    }

    @Test
    void untrackedUntaggedOutputMayProceed() {
        assertEquals(
            CraftOutputPolicy.Action.ALLOW_UNTRACKED,
            policy.decide(false, false, false)
        );
    }

    /**
     * C1 (review 2026-09-17): cancelling every crafted pickaxe is a large thing to do to a server,
     * and the page that sold the plugin did not say it. The server can now say which it wants — but
     * only for this one branch.
     */
    @Test
    void aServerThatAllowsUntrackedCraftOutputGetsItsCraftThrough() {
        assertEquals(
            CraftOutputPolicy.Action.ALLOW_UNTAGGED_ELIGIBLE,
            policy.decide(false, false, true, false)
        );
    }

    @Test
    void theAllowSwitchNeverOpensTheTaggedBranches() {
        assertEquals(
            CraftOutputPolicy.Action.CANCEL_TAGGED_READY_COPY,
            policy.decide(true, true, true, false),
            "a crafted copy of a tagged item still copies an identity"
        );
        assertEquals(
            CraftOutputPolicy.Action.CANCEL_TAGGED_NOT_READY,
            policy.decide(true, false, true, false),
            "an identity that cannot be verified is still refused"
        );
    }

    @Test
    void thePolicyOnItsOwnStillCancels() {
        assertEquals(
            CraftOutputPolicy.Action.CANCEL_UNTAGGED_ELIGIBLE,
            policy.decide(false, false, true),
            "the default must stay the fail-closed one: a missing config key cannot open this"
        );
    }
}
