namespace ItemGuard.Execution {
    // Pure launch invariants shared by native creation and its offline contracts.
    public static class WorkerLaunchPolicy {
        public const string SystemCommandProcessor = "C:\\Windows\\System32\\cmd.exe";
        public const string SystemWorkingDirectory = "C:\\Windows";

        public static string BuildExitCommandLine() {
            return "\"" + SystemCommandProcessor + "\" /d /s /c exit 0";
        }

        public static bool MustTerminateAfterContainmentFailure() { return true; }

        // Release authority is one-shot even when ResumeThread returns an error or
        // an unexpected previous suspend count.
        public static bool MustPoisonAfterReleaseAttempt(uint previousSuspendCount) { return true; }
    }
}
