namespace ItemGuard.Execution {
    // Pure contract for the native STARTUPINFOEX boundary. The native adapter must
    // supply a three-handle list before it may request bInheritHandles=true.
    public static class WorkerStartupPolicy {
        public const uint CreateSuspended = 0x00000004;
        public const uint CreateUnicodeEnvironment = 0x00000400;
        public const uint CreateNoWindow = 0x08000000;
        public const uint ExtendedStartupInfoPresent = 0x00080000;
        public const uint StartfUseStdHandles = 0x00000100;
        public const uint ProcThreadAttributeHandleList = 0x00020002;
        public const int InheritedHandleCount = 3;

        public static uint CreateFlags {
            get { return CreateSuspended | CreateUnicodeEnvironment | CreateNoWindow | ExtendedStartupInfoPresent; }
        }
        public static uint StartupFlags { get { return StartfUseStdHandles; } }
        public static uint HandleListAttribute { get { return ProcThreadAttributeHandleList; } }
        public static bool UsesExtendedStartupInfo { get { return true; } }
        public static bool UsesStandardHandles { get { return true; } }
        public static bool InheritsOnlyExplicitStdioHandles { get { return true; } }
        public static bool AllowsAmbientHandleInheritance { get { return false; } }
        public static bool AllowsConsoleFallback { get { return false; } }
    }
}
