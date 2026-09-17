using System;
using ItemGuard.Execution;

class NativeSuspendedWorkerFailurePolicyTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }

    static int Main() {
        try {
            Check(!NativeSuspendedWorker.MustTerminateAfterResumeResult(1),
                "exact first resume result permits the worker to continue");
            Check(NativeSuspendedWorker.MustTerminateAfterResumeResult(0),
                "already-running result must terminate the owned worker before failure");
            Check(NativeSuspendedWorker.MustTerminateAfterResumeResult(2),
                "extra-suspension result must terminate rather than leave an uncertain worker");
            Check(NativeSuspendedWorker.MustTerminateAfterResumeResult(uint.MaxValue),
                "ResumeThread API failure must terminate the owned worker before failure");
            Console.WriteLine("NATIVE_SUSPENDED_WORKER_FAILURE_POLICY checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
    }
}
