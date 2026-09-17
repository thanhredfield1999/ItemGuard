using System;
using ItemGuard.Execution;

class NativeWorkerStdioPolicyTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }

    static int Main() {
        try {
            var plan = NativeWorkerStdioPolicy.Create();
            Check(plan.RequiresHandleInheritance, "only explicit child pipe endpoints require CreateProcess handle inheritance");
            Check(plan.ChildStdinInheritable && plan.ChildStdoutInheritable && plan.ChildStderrInheritable,
                "all three child stdio endpoints are intentionally inheritable");
            Check(!plan.GuardianStdinInheritable && !plan.GuardianStdoutInheritable && !plan.GuardianStderrInheritable,
                "guardian pipe endpoints cannot leak into the child");
            Check(plan.HasDistinctStandardStreams, "stdin, stdout and stderr remain distinct protocol surfaces");
            Check(!plan.AllowsConsoleFallback, "worker never receives ambient console handles as a fallback");
            Console.WriteLine("NATIVE_WORKER_STDIO_POLICY checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
    }
}
