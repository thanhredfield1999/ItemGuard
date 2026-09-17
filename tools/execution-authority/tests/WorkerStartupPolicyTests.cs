using System;
using ItemGuard.Execution;

class WorkerStartupPolicyTests {
    static int checks;
    static void Check(bool value, string label) { checks++; if (!value) throw new Exception(label); }

    static int Main() {
        try {
            Check(WorkerStartupPolicy.UsesExtendedStartupInfo,
                "worker launch requires STARTUPINFOEX for explicit inheritance filtering");
            Check(WorkerStartupPolicy.UsesStandardHandles,
                "worker launch binds the three pipe endpoints as standard handles");
            Check(WorkerStartupPolicy.InheritsOnlyExplicitStdioHandles,
                "bInheritHandles is authorized only with the explicit three-handle list");
            Check(!WorkerStartupPolicy.AllowsAmbientHandleInheritance,
                "ambient inheritable guardian handles cannot leak into the worker");
            Check(!WorkerStartupPolicy.AllowsConsoleFallback,
                "worker cannot fall back to inherited console handles");
            Check(WorkerStartupPolicy.CreateFlags == 0x08080404u,
                "worker launch flags are exactly suspended unicode no-window extended-startup");
            Check(WorkerStartupPolicy.StartupFlags == 0x00000100u,
                "worker startup flags require the supplied standard handles");
            Check(WorkerStartupPolicy.HandleListAttribute == 0x00020002u,
                "worker inheritance filter uses PROC_THREAD_ATTRIBUTE_HANDLE_LIST");
            Check(WorkerStartupPolicy.InheritedHandleCount == 3,
                "only stdin stdout and stderr are admitted to the child handle list");
            Console.WriteLine("WORKER_STARTUP_POLICY checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) { Console.WriteLine("FAIL " + ex); return 1; }
    }
}
