using System;
using ItemGuard.Execution;

class NativeSuspendedWorkerReleaseTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }

    static int Main() {
        try {
            Check(NativeSuspendedWorker.IsExactFirstResumeResult(1),
                "only the initial suspended count authorizes guardian release");
            Check(!NativeSuspendedWorker.IsExactFirstResumeResult(0),
                "an already-running worker cannot be recorded as a guardian release");
            Check(!NativeSuspendedWorker.IsExactFirstResumeResult(2),
                "extra suspension cannot be silently released by the guardian");
            Check(!NativeSuspendedWorker.IsExactFirstResumeResult(uint.MaxValue),
                "ResumeThread failure cannot be recorded as a guardian release");
            Check(NativeSuspendedWorker.RequiresExplicitStdioHandleList,
                "worker launch permits inheritance only for the explicit stdio handle list");
            Console.WriteLine("NATIVE_SUSPENDED_WORKER_RELEASE checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
    }
}
