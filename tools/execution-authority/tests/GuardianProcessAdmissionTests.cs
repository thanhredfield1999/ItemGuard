using System;
using ItemGuard.Execution;

class GuardianProcessAdmissionTests {
    static int checks;
    static void Check(bool value, string label) { checks++; if (!value) throw new Exception(label); }

    static int Main() {
        try {
            var gate = new GuardianProcessAdmission();
            Check(!gate.TryRecordProcessCreatedSuspended(), "a process cannot exist before the guardian has durably recorded spawn intent");
            Check(gate.Failed && gate.Lifecycle.Process == "ABSENT", "out-of-order creation fails closed without lifecycle mutation");

            gate = new GuardianProcessAdmission();
            Check(gate.TryRecordSpawnIntent(), "first durable spawn intent enters the lifecycle");
            Check(gate.TryRecordProcessCreatedSuspended(), "suspended creation follows spawn intent");
            Check(gate.Lifecycle.Process == "CREATED_SUSPENDED", "creation remains non-execution evidence");
            Check(!gate.TryRecordAdmissionReady(true, true, false), "missing pipe admission blocks release authority");
            Check(gate.Failed && gate.Lifecycle.Process == "CREATED_SUSPENDED", "failed admission cannot promote lifecycle state");

            gate = new GuardianProcessAdmission();
            Check(gate.TryRecordSpawnIntent() && gate.TryRecordProcessCreatedSuspended(), "pre-termination prefix is accepted");
            Check(gate.TryRecordSuspendedTerminationIntent(), "only an unreleased suspended worker may enter termination intent");
            Check(gate.TryRecordSuspendedTerminationObserved(true), "observed termination with unchanged thread time establishes non-execution");
            Check(gate.Lifecycle.Process == "TERMINATED_SUSPENDED" && gate.Lifecycle.Code == "NON_EXECUTION_ESTABLISHED",
                "terminated suspended worker is distinct from a release failure");

            gate = new GuardianProcessAdmission();
            Check(gate.TryRecordSpawnIntent() && gate.TryRecordProcessCreatedSuspended()
                && gate.TryRecordSuspendedTerminationIntent(), "second pre-termination prefix is accepted");
            Check(!gate.TryRecordSuspendedTerminationObserved(false), "thread-time increase prevents a non-execution claim");
            Check(gate.Failed && gate.Lifecycle.Process == "SUSPENDED_TERMINATION_INTENT_UNCERTAIN",
                "failed non-execution observation remains uncertain");

            gate = new GuardianProcessAdmission();
            Check(gate.TryRecordSpawnIntent() && gate.TryRecordProcessCreatedSuspended(), "valid pre-admission prefix is accepted");
            Check(gate.TryRecordAdmissionReady(true, true, true), "job, identity and pipe checks jointly admit the still-suspended worker");
            Check(gate.Lifecycle.Process == "ADMISSION_READY", "admission-ready remains distinct from release");
            Check(gate.TryRecordReleaseIntent(), "only an admitted worker receives a release intent");
            Check(!gate.TryRecordReleaseObserved(0), "unexpected ResumeThread result cannot become release observed");
            Check(gate.Failed && gate.Lifecycle.Process == "RELEASE_INTENT_UNCERTAIN", "failed resume result leaves execution uncertain rather than claiming non-execution");

            gate = new GuardianProcessAdmission();
            Check(gate.TryRecordSpawnIntent() && gate.TryRecordProcessCreatedSuspended()
                && gate.TryRecordAdmissionReady(true, true, true) && gate.TryRecordReleaseIntent(), "complete pre-release prefix is accepted");
            Check(gate.TryRecordReleaseObserved(1), "exact first ResumeThread result records release observed");
            Check(gate.Lifecycle.Process == "RELEASED" && gate.Lifecycle.Code == "NOT_ESTABLISHED", "release observed is not execution activity");
            Check(!gate.TryRecordReleaseIntent(), "release authority is one-shot");

            gate = new GuardianProcessAdmission();
            Check(gate.TryRecordSpawnIntent() && gate.TryRecordProcessCreatedSuspended()
                && gate.TryRecordAdmissionReady(true, true, true), "callback release gets only an admitted worker");
            int resumeCalls = 0;
            Check(gate.TryRelease(() => { resumeCalls++; return 1; }),
                "one coordinator call binds release intent and the exact resume return together");
            Check(resumeCalls == 1 && gate.Lifecycle.Process == "RELEASED",
                "the release callback is invoked exactly once before release-observed evidence");

            gate = new GuardianProcessAdmission();
            Check(gate.TryRecordSpawnIntent() && gate.TryRecordProcessCreatedSuspended()
                && gate.TryRecordAdmissionReady(true, true, true), "failed resume callback gets an admitted prefix");
            resumeCalls = 0;
            Check(!gate.TryRelease(() => { resumeCalls++; return 0; }),
                "unexpected native resume return rejects the coordinated release");
            Check(resumeCalls == 1 && gate.Failed && gate.Lifecycle.Process == "RELEASE_INTENT_UNCERTAIN",
                "failed native release cannot be retried or recast as non-execution");

            Console.WriteLine("GUARDIAN_PROCESS_ADMISSION checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
    }
}
