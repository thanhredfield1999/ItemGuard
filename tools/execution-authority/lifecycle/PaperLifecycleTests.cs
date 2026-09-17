using System;
using ItemGuard.Execution;
class PaperLifecycleTests {
    static int Main() {
        var state = new PaperLifecycle();
        if (state.Process != "ABSENT") return 1;
        if (!state.Apply("SPAWN_INTENT") || state.Process != "SPAWN_INTENT_ONLY") { Console.WriteLine("FAIL spawn transition"); return 1; }
        if (!state.Apply("PROCESS_CREATED_SUSPENDED") || state.Process != "CREATED_SUSPENDED" || state.Code != "NOT_ESTABLISHED" || state.Output != "NOT_ESTABLISHED" || state.Exit != "NOT_ESTABLISHED") { Console.WriteLine("FAIL created axes"); return 1; }
        if (state.Apply("EXECUTION_RELEASE_INTENT") || !state.InvalidSuffix) { Console.WriteLine("FAIL release bypassed admission-ready"); return 1; }
        state = new PaperLifecycle(); state.Apply("SPAWN_INTENT"); state.Apply("PROCESS_CREATED_SUSPENDED");
        if (!state.Apply("PROCESS_ADMISSION_READY") || state.Process != "ADMISSION_READY") { Console.WriteLine("FAIL admission-ready transition"); return 1; }
        if (!state.Apply("EXECUTION_RELEASE_INTENT") || state.Process != "RELEASE_INTENT_UNCERTAIN") { Console.WriteLine("FAIL release after admission-ready"); return 1; }
        Console.WriteLine("PASS spawn/created axes"); return 0;
    }
}
