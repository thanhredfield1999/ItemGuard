namespace ItemGuard.Execution {
    // Pure handle-inheritance plan for the guardian-owned stdio boundary.
    // Native pipe creation and post-create handle closure remain outside this policy.
    public sealed class NativeWorkerStdioPolicy {
        public bool RequiresHandleInheritance { get; private set; }
        public bool ChildStdinInheritable { get; private set; }
        public bool ChildStdoutInheritable { get; private set; }
        public bool ChildStderrInheritable { get; private set; }
        public bool GuardianStdinInheritable { get; private set; }
        public bool GuardianStdoutInheritable { get; private set; }
        public bool GuardianStderrInheritable { get; private set; }
        public bool HasDistinctStandardStreams { get; private set; }
        public bool AllowsConsoleFallback { get; private set; }

        NativeWorkerStdioPolicy() {
            RequiresHandleInheritance = true;
            ChildStdinInheritable = true;
            ChildStdoutInheritable = true;
            ChildStderrInheritable = true;
            GuardianStdinInheritable = false;
            GuardianStdoutInheritable = false;
            GuardianStderrInheritable = false;
            HasDistinctStandardStreams = true;
            AllowsConsoleFallback = false;
        }

        public static NativeWorkerStdioPolicy Create() {
            return new NativeWorkerStdioPolicy();
        }
    }
}
