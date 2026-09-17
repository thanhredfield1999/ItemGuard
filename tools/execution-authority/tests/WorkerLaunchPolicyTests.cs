using System;
using ItemGuard.Execution;

class WorkerLaunchPolicyTests {
    static int checks;
    static void Check(bool value, string label) { checks++; if (!value) throw new Exception(label); }

    static int Main() {
        try {
            Check(WorkerLaunchPolicy.SystemCommandProcessor == "C:\\Windows\\System32\\cmd.exe",
                "worker command processor is pinned rather than inherited from ComSpec");
            Check(WorkerLaunchPolicy.BuildExitCommandLine() == "\"C:\\Windows\\System32\\cmd.exe\" /d /s /c exit 0",
                "worker command line has fixed exact image and arguments");
            Check(WorkerLaunchPolicy.MustTerminateAfterContainmentFailure(),
                "a worker created before failed job admission must be terminated before handles are closed");
            Check(WorkerLaunchPolicy.MustPoisonAfterReleaseAttempt(0)
                && WorkerLaunchPolicy.MustPoisonAfterReleaseAttempt(1)
                && WorkerLaunchPolicy.MustPoisonAfterReleaseAttempt(uint.MaxValue),
                "every ResumeThread attempt consumes the sole release authority regardless of result");
            Console.WriteLine("WORKER_LAUNCH_POLICY checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) { Console.WriteLine("FAIL " + ex); return 1; }
    }
}
