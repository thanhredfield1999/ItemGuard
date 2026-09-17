package smoke;

/**
 * Behaviour tests for {@link CraftCloseLifecycle}. No server, no Paper, no classpath.
 *
 * <p>Each test isolates one thing a crafting-close case can get wrong in a way a passing receipt
 * would not show: a close that belonged to some other window, a listener left registered on a path
 * that did not report, a server call that threw being filed as a close with no acknowledgement, and
 * a recovery that clears a slot it does not own. All four were reachable in the probe and none of
 * them is visible from the receipt grammar, which is why they are adjudicated here instead.
 *
 * <p>Plain {@code main} rather than JUnit: this compiles and runs with nothing but the JDK, so the
 * Python gate that drives it needs no dependency the harness does not already have.
 */
public final class CraftCloseLifecycleTest {

    private static int failures;

    public static void main(String[] args) {
        run("close_from_another_view_is_not_this_cases_close",
            CraftCloseLifecycleTest::close_from_another_view_is_not_this_cases_close);
        run("close_from_a_same_typed_second_view_is_not_this_cases_close",
            CraftCloseLifecycleTest::close_from_a_same_typed_second_view_is_not_this_cases_close);
        run("close_from_the_armed_view_is_accepted_with_its_acknowledgement",
            CraftCloseLifecycleTest::close_from_the_armed_view_is_accepted_with_its_acknowledgement);
        run("a_capacity_read_that_throws_fails_the_case_explicitly",
            CraftCloseLifecycleTest::a_capacity_read_that_throws_fails_the_case_explicitly);
        run("a_capacity_read_that_throws_does_not_leave_a_closed_case_behind",
            CraftCloseLifecycleTest::a_capacity_read_that_throws_does_not_leave_a_closed_case_behind);
        run("every_terminal_outcome_releases_the_armed_listener",
            CraftCloseLifecycleTest::every_terminal_outcome_releases_the_armed_listener);
        run("a_close_offered_after_a_terminal_outcome_is_ignored",
            CraftCloseLifecycleTest::a_close_offered_after_a_terminal_outcome_is_ignored);
        run("recovery_reclaims_the_filler_slots_this_case_stamped",
            CraftCloseLifecycleTest::recovery_reclaims_the_filler_slots_this_case_stamped);
        run("recovery_never_reclaims_a_slot_that_now_holds_the_original",
            CraftCloseLifecycleTest::recovery_never_reclaims_a_slot_that_now_holds_the_original);
        run("recovery_never_reclaims_filler_this_case_did_not_write",
            CraftCloseLifecycleTest::recovery_never_reclaims_filler_this_case_did_not_write);
        run("recovery_keeps_a_genuine_stack_of_the_filler_material",
            CraftCloseLifecycleTest::recovery_keeps_a_genuine_stack_of_the_filler_material);
        run("recovery_keeps_filler_another_case_stamped",
            CraftCloseLifecycleTest::recovery_keeps_filler_another_case_stamped);
        run("every_terminal_outcome_gives_the_filler_back",
            CraftCloseLifecycleTest::every_terminal_outcome_gives_the_filler_back);
        run("a_capacity_read_that_throws_gives_the_filler_back",
            CraftCloseLifecycleTest::a_capacity_read_that_throws_gives_the_filler_back);
        run("a_cleanup_that_throws_is_not_a_clean_ending",
            CraftCloseLifecycleTest::a_cleanup_that_throws_is_not_a_clean_ending);
        run("a_case_whose_cleanup_failed_is_not_acceptable",
            CraftCloseLifecycleTest::a_case_whose_cleanup_failed_is_not_acceptable);
        run("a_case_that_ended_cleanly_is_acceptable",
            CraftCloseLifecycleTest::a_case_that_ended_cleanly_is_acceptable);
        run("a_failed_cleanup_still_leaves_the_case_releasable_by_its_caller",
            CraftCloseLifecycleTest::a_failed_cleanup_still_leaves_the_case_releasable_by_its_caller);
        run("a_case_that_never_ended_is_not_acceptable",
            CraftCloseLifecycleTest::a_case_that_never_ended_is_not_acceptable);
        run("a_mid_fill_failure_leaves_no_filler_behind",
            CraftCloseLifecycleTest::a_mid_fill_failure_leaves_no_filler_behind);
        run("an_arm_that_rejects_its_inputs_writes_nothing",
            CraftCloseLifecycleTest::an_arm_that_rejects_its_inputs_writes_nothing);
        run("a_completed_fill_arms_a_case_that_owns_every_written_slot",
            CraftCloseLifecycleTest::a_completed_fill_arms_a_case_that_owns_every_written_slot);
        run("the_filler_is_given_back_exactly_once",
            CraftCloseLifecycleTest::the_filler_is_given_back_exactly_once);
        if (failures > 0) {
            System.out.println("FAILED " + failures);
            System.exit(1);
        }
        System.out.println("OK");
    }

    /**
     * Runs one test and keeps going.
     *
     * <p>A case that throws is one failed case, not the end of the run: a harness that stops at the
     * first one reports a single symptom for a contract that is unmet in several places, which is
     * exactly how this stage was read as "one error" rather than as the list of things to fix.
     */
    private static void run(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable failure) {
            failures++;
            System.out.println("FAIL " + name + ": threw " + failure);
        }
    }

    /** A view is an identity, not a kind: two windows of one kind are two different windows. */
    private static final class View {
        private final String type;
        private final String name;
        private View(String type, String name) {
            this.type = type;
            this.name = name;
        }
        @Override public String toString() {
            return type + "/" + name;
        }
    }

    private static CraftCloseLifecycle armed(Object view, int... filled) {
        return CraftCloseLifecycle.arm("spare", view, filled, "craftclose-spare-0011");
    }

    private static CraftCloseLifecycle.Capacity capacity(int value) {
        return () -> value;
    }

    private static CraftCloseLifecycle.Capacity throwing() {
        return () -> {
            throw new IllegalStateException("inventory read failed");
        };
    }

    // --- Which close belongs to this case. ---

    private static void close_from_another_view_is_not_this_cases_close() {
        View armedView = new View("CRAFTING", "staff-inventory");
        CraftCloseLifecycle lifecycle = armed(armedView);
        boolean accepted = lifecycle.offerClose(
            new View("CHEST", "some-chest"), "PLAYER", capacity(27));
        is(false, accepted, "a close of another window was accepted");
        is(false, lifecycle.closed(), "another window's close marked this case closed");
        is(true, lifecycle.armed(), "another window's close disarmed this case");
    }

    private static void close_from_a_same_typed_second_view_is_not_this_cases_close() {
        // The staff actor opens more than one window over a run and two of them are the same kind,
        // so a case that matches on the type alone adjudicates somebody else's close as its own.
        View armedView = new View("CRAFTING", "first");
        CraftCloseLifecycle lifecycle = armed(armedView);
        boolean accepted = lifecycle.offerClose(
            new View("CRAFTING", "second"), "PLAYER", capacity(27));
        is(false, accepted, "a same-typed but different window's close was accepted");
        is(false, lifecycle.closed(), "a same-typed but different window closed this case");
    }

    private static void close_from_the_armed_view_is_accepted_with_its_acknowledgement() {
        View armedView = new View("CRAFTING", "staff-inventory");
        CraftCloseLifecycle lifecycle = armed(armedView);
        boolean accepted = lifecycle.offerClose(armedView, "PLAYER", capacity(27));
        is(true, accepted, "this case's own close was not accepted");
        is(true, lifecycle.closed(), "this case's own close did not close it");
        is("CLIENT", lifecycle.ack(), "a PLAYER close was not read as the client's");
        is(27, lifecycle.slotsFree(), "capacity was not captured at the accepted close");
    }

    // --- A server call that threw is not a close with no acknowledgement. ---

    private static void a_capacity_read_that_throws_fails_the_case_explicitly() {
        View armedView = new View("CRAFTING", "staff-inventory");
        CraftCloseLifecycle lifecycle = armed(armedView);
        lifecycle.offerClose(armedView, "PLAYER", throwing());
        is(CraftCloseLifecycle.Outcome.EXCEPTION, lifecycle.outcome(),
            "a throwing capacity read was not reported as a failure");
        is(true, lifecycle.released(), "a throwing capacity read left the listener armed");
    }

    private static void a_capacity_read_that_throws_does_not_leave_a_closed_case_behind() {
        // The quiet version of the bug: the case records closed=true, the acknowledgement never
        // gets written, and the receipt then reads as a close that simply was not the client's.
        // That is a different verdict from "this run could not be measured".
        View armedView = new View("CRAFTING", "staff-inventory");
        CraftCloseLifecycle lifecycle = armed(armedView);
        lifecycle.offerClose(armedView, "PLAYER", throwing());
        is(false, lifecycle.closed() && "NONE".equals(lifecycle.ack()),
            "a failed read was filed as a close with no acknowledgement");
    }

    // --- Every terminal path gives the listener back. ---

    private static void every_terminal_outcome_releases_the_armed_listener() {
        for (CraftCloseLifecycle.Outcome outcome : new CraftCloseLifecycle.Outcome[]{
                CraftCloseLifecycle.Outcome.SUCCESS,
                CraftCloseLifecycle.Outcome.UNACCOUNTED,
                CraftCloseLifecycle.Outcome.EXCEPTION,
                CraftCloseLifecycle.Outcome.TIMEOUT}) {
            CraftCloseLifecycle lifecycle = armed(new View("CRAFTING", "staff-inventory"));
            is(false, lifecycle.released(), outcome + ": released before it ended");
            lifecycle.finish(outcome);
            is(true, lifecycle.released(), outcome + ": ended without releasing the listener");
            is(false, lifecycle.armed(), outcome + ": ended still armed");
            is(outcome, lifecycle.outcome(), outcome + ": ended under the wrong outcome");
        }
    }

    private static void a_close_offered_after_a_terminal_outcome_is_ignored() {
        // A released case must be inert. One that still answers is one whose listener outlived it,
        // which is how a later case gets adjudicated on an earlier case's acknowledgement.
        View armedView = new View("CRAFTING", "staff-inventory");
        CraftCloseLifecycle lifecycle = armed(armedView);
        lifecycle.finish(CraftCloseLifecycle.Outcome.TIMEOUT);
        boolean accepted = lifecycle.offerClose(armedView, "PLAYER", capacity(27));
        is(false, accepted, "a finished case accepted a later close");
        is(CraftCloseLifecycle.Outcome.TIMEOUT, lifecycle.outcome(),
            "a later close rewrote a finished case's outcome");
    }

    // --- Recovery gives back what this case took, and nothing else. ---

    private static final String MARKER = "craftclose-full-7f3a";

    private static CraftCloseLifecycle stamped(int... filled) {
        return CraftCloseLifecycle.arm("full", new View("CRAFTING", "staff-inventory"),
            filled, MARKER);
    }

    private static void recovery_reclaims_the_filler_slots_this_case_stamped() {
        CraftCloseLifecycle lifecycle = stamped(1, 2, 3);
        int[] reclaim = lifecycle.reclaimable(slot -> MARKER);
        is("[1, 2, 3]", java.util.Arrays.toString(reclaim),
            "recovery did not give back the filler it wrote");
    }

    private static void recovery_never_reclaims_a_slot_that_now_holds_the_original() {
        // Paper can fit the returned stack into a slot this case filled once the filler moved, and
        // clearing that slot would delete the very identity the recovery exists to hand back.
        CraftCloseLifecycle lifecycle = stamped(1, 2, 3);
        int[] reclaim = lifecycle.reclaimable(slot -> slot == 2 ? null : MARKER);
        is("[1, 3]", java.util.Arrays.toString(reclaim),
            "recovery cleared a slot holding something other than its own filler");
    }

    private static void recovery_never_reclaims_filler_this_case_did_not_write() {
        CraftCloseLifecycle lifecycle = stamped(1, 2);
        int[] reclaim = lifecycle.reclaimable(slot -> MARKER);
        is("[1, 2]", java.util.Arrays.toString(reclaim),
            "recovery cleared a slot it never filled");
    }

    private static void recovery_keeps_a_genuine_stack_of_the_filler_material() {
        // The defect a material match cannot see: the actor's own cobblestone, or a stack vanilla
        // moved, sitting in a slot this case filled. It is the same material and it is NOT this
        // case's filler, so clearing it destroys an item the harness never put there.
        CraftCloseLifecycle lifecycle = stamped(1, 2, 3);
        int[] reclaim = lifecycle.reclaimable(slot -> slot == 2 ? null : MARKER);
        is(false, java.util.Arrays.toString(reclaim).contains("2"),
            "recovery deleted a genuine stack of the filler material");
    }

    private static void recovery_keeps_filler_another_case_stamped() {
        CraftCloseLifecycle lifecycle = stamped(1, 2, 3);
        int[] reclaim = lifecycle.reclaimable(
            slot -> slot == 2 ? "craftclose-spare-0011" : MARKER);
        is("[1, 3]", java.util.Arrays.toString(reclaim),
            "recovery cleared filler belonging to another case");
    }

    // --- Giving the room back is an obligation of ending, not of succeeding. ---

    private static void every_terminal_outcome_gives_the_filler_back() {
        for (CraftCloseLifecycle.Outcome outcome : new CraftCloseLifecycle.Outcome[]{
                CraftCloseLifecycle.Outcome.SUCCESS,
                CraftCloseLifecycle.Outcome.UNACCOUNTED,
                CraftCloseLifecycle.Outcome.EXCEPTION,
                CraftCloseLifecycle.Outcome.TIMEOUT}) {
            int[] ran = {0};
            CraftCloseLifecycle lifecycle = stamped(1, 2, 3);
            lifecycle.onRelease(() -> ran[0]++);
            is(0, ran[0], outcome + ": the filler was given back before the case ended");
            lifecycle.finish(outcome);
            is(1, ran[0], outcome + ": ended without giving the filler back");
        }
    }

    private static void a_capacity_read_that_throws_gives_the_filler_back() {
        // The path a reviewer signed off as non-blocking: the case fails inside the close handler,
        // so nothing downstream ever reaches the recovery step and the room stays taken.
        View armedView = new View("CRAFTING", "staff-inventory");
        int[] ran = {0};
        CraftCloseLifecycle lifecycle = CraftCloseLifecycle.arm(
            "full", armedView, new int[]{1, 2, 3}, MARKER);
        lifecycle.onRelease(() -> ran[0]++);
        lifecycle.offerClose(armedView, "PLAYER", throwing());
        is(CraftCloseLifecycle.Outcome.EXCEPTION, lifecycle.outcome(),
            "a throwing capacity read was not reported as a failure");
        is(1, ran[0], "a throwing capacity read left the filler in the inventory");
    }

    private static void a_cleanup_that_throws_is_not_a_clean_ending() {
        // Silence here would be the worst version of the bug: the case reports its receipt, the
        // room is still taken, and the next case is read against an inventory nobody knows is full.
        CraftCloseLifecycle lifecycle = stamped(1, 2, 3);
        lifecycle.onRelease(() -> {
            throw new IllegalStateException("slot write refused");
        });
        lifecycle.finish(CraftCloseLifecycle.Outcome.SUCCESS);
        is(true, lifecycle.cleanupFailed(), "a throwing cleanup was reported as a clean ending");
        is(false, lifecycle.cleanupRan(), "a throwing cleanup was reported as having run");
        is(CraftCloseLifecycle.Outcome.EXCEPTION, lifecycle.outcome(),
            "a case whose cleanup threw kept a success outcome");
        is(true, lifecycle.released(), "a throwing cleanup also leaked the subscription");
        is("slot write refused", lifecycle.cleanupFailure().getMessage(),
            "the cleanup failure was swallowed instead of kept as a diagnostic");
    }

    // --- A case that could not give the room back has not passed. ---

    private static void a_case_whose_cleanup_failed_is_not_acceptable() {
        // The probe prints its receipt and then calls check(...). Nothing in that check consults
        // how the case ended, so a case that failed to give the room back still prints PASS while
        // the failure sits in a separate LITE_PROBE_FAIL line.
        CraftCloseLifecycle lifecycle = stamped(1, 2, 3);
        lifecycle.onRelease(() -> {
            throw new IllegalStateException("slot write refused");
        });
        lifecycle.finish(CraftCloseLifecycle.Outcome.SUCCESS);
        is(false, lifecycle.endedCleanly(),
            "a case that could not give the room back was still acceptable");
    }

    private static void a_case_that_ended_cleanly_is_acceptable() {
        // Control for the test above: without it, an always-false predicate would satisfy it.
        CraftCloseLifecycle lifecycle = stamped(1, 2, 3);
        lifecycle.onRelease(() -> { });
        lifecycle.finish(CraftCloseLifecycle.Outcome.SUCCESS);
        is(true, lifecycle.endedCleanly(), "a clean ending was not acceptable");
    }

    private static void a_failed_cleanup_still_leaves_the_case_releasable_by_its_caller() {
        // What the probe's failure `finally` stands on. It ends the case and then, in its own
        // finally, gives the subscription back — so a cleanup that threw must still leave the case
        // ended and released, and a later finish (recovery, onDisable) must not run the cleanup a
        // second time or move the outcome it failed under.
        int[] attempts = {0};
        CraftCloseLifecycle lifecycle = stamped(1, 2, 3);
        lifecycle.onRelease(() -> {
            attempts[0]++;
            throw new IllegalStateException("slot write refused");
        });
        lifecycle.finish(CraftCloseLifecycle.Outcome.EXCEPTION);
        is(true, lifecycle.released(),
            "a case whose cleanup threw was not left releasable by its caller");
        is(false, lifecycle.armed(), "a case whose cleanup threw was left armed");
        lifecycle.finish(CraftCloseLifecycle.Outcome.TIMEOUT);
        is(1, attempts[0], "a second ending retried a cleanup that had already failed");
        is(CraftCloseLifecycle.Outcome.EXCEPTION, lifecycle.outcome(),
            "a second ending moved the outcome the case failed under");
        is(false, lifecycle.endedCleanly(), "a case whose cleanup threw was still acceptable");
    }

    private static void a_case_that_never_ended_is_not_acceptable() {
        is(false, stamped(1, 2, 3).endedCleanly(), "a case still armed was acceptable");
    }

    // --- Taking the room and arming are one step or neither. ---

    private static void a_mid_fill_failure_leaves_no_filler_behind() {
        // The write that fails is the third of four. The two already written are in an inventory no
        // lifecycle owns yet, so nothing downstream will ever give them back.
        java.util.List<Integer> written = new java.util.ArrayList<>();
        java.util.List<Integer> cleared = new java.util.ArrayList<>();
        try {
            CraftCloseLifecycle.armFilled("full", new View("CRAFTING", "staff-inventory"), MARKER,
                new int[]{1, 2, 3, 4},
                slot -> {
                    if (slot == 3) {
                        throw new IllegalStateException("slot write refused");
                    }
                    written.add(slot);
                },
                cleared::add);
            failed("a fill that could not finish was reported as an armed case");
        } catch (Exception expected) {
            is("slot write refused", expected.getMessage(),
                "the write failure was swallowed instead of raised");
        }
        is("[1, 2]", written.toString(), "the test's own writer did not write what it claims");
        is("[1, 2]", cleared.toString(),
            "a fill that failed half way through left its filler in the inventory");
    }

    private static void an_arm_that_rejects_its_inputs_writes_nothing() {
        // Validation belongs before the first write. Rejecting afterwards is the same stranded
        // filler by a different route.
        java.util.List<Integer> written = new java.util.ArrayList<>();
        try {
            CraftCloseLifecycle.armFilled("full", new View("CRAFTING", "staff-inventory"), "",
                new int[]{1, 2, 3}, written::add, slot -> { });
            failed("an arm with no marker was accepted");
        } catch (Exception expected) {
            is(true, expected instanceof IllegalArgumentException,
                "an arm with no marker failed for the wrong reason: " + expected);
        }
        is("[]", written.toString(), "a rejected arm had already taken the room");
    }

    private static void a_completed_fill_arms_a_case_that_owns_every_written_slot() {
        java.util.List<Integer> written = new java.util.ArrayList<>();
        try {
            CraftCloseLifecycle lifecycle = CraftCloseLifecycle.armFilled(
                "full", new View("CRAFTING", "staff-inventory"), MARKER,
                new int[]{1, 2, 3}, written::add, slot -> { });
            is("[1, 2, 3]", written.toString(), "the fill did not write every slot");
            is("[1, 2, 3]",
                java.util.Arrays.toString(lifecycle.reclaimable(slot -> MARKER)),
                "the armed case does not own every slot that was written");
        } catch (Exception failure) {
            failed("a fill that completed did not arm a case: " + failure);
        }
    }

    private static void the_filler_is_given_back_exactly_once() {
        // finishArmedClose() is called again by the recovery step and by onDisable. Returning the
        // room twice would clear slots the run has since put something else into.
        int[] ran = {0};
        CraftCloseLifecycle lifecycle = stamped(1, 2, 3);
        lifecycle.onRelease(() -> ran[0]++);
        lifecycle.finish(CraftCloseLifecycle.Outcome.TIMEOUT);
        lifecycle.finish(CraftCloseLifecycle.Outcome.SUCCESS);
        is(1, ran[0], "the filler was given back more than once");
        is(CraftCloseLifecycle.Outcome.TIMEOUT, lifecycle.outcome(),
            "a second ending rewrote the outcome the case ended under");
    }

    /** Records a failure for a path that should not have been reachable at all. */
    private static void failed(String message) {
        failures++;
        System.out.println("FAIL " + message);
    }

    private static void is(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            failures++;
            System.out.println("FAIL " + message + ": expected " + expected + ", was " + actual);
        }
    }
}
