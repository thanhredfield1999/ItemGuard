using System;
using ItemGuard.Execution;

class PaperAxisVectorTests {
    static int cases;
    static void Expect(PaperLifecycle s, string expected) {
        string actual = s.Process + "/" + s.Code + "/" + s.Output + "/" + s.Exit;
        if (actual != expected) throw new Exception("want " + expected + ", got " + actual);
        cases++;
    }
    static void Branch(string[] events, string[] expected) {
        var s = new PaperLifecycle();
        Expect(s, "ABSENT/NOT_APPLICABLE/NOT_APPLICABLE/NOT_APPLICABLE");
        for (int i = 0; i < events.Length; i++) {
            if (!s.Apply(events[i])) throw new Exception("valid event rejected " + events[i]);
            Expect(s, expected[i]);
        }
    }
    static int Main() {
        const string spawned = "SPAWN_INTENT_ONLY/NOT_APPLICABLE/NOT_APPLICABLE/NOT_APPLICABLE";
        const string created = "CREATED_SUSPENDED/NOT_ESTABLISHED/NOT_ESTABLISHED/NOT_ESTABLISHED";
        const string admitted = "ADMISSION_READY/NOT_ESTABLISHED/NOT_ESTABLISHED/NOT_ESTABLISHED";
        const string intent = "RELEASE_INTENT_UNCERTAIN/NOT_ESTABLISHED/NOT_ESTABLISHED/NOT_ESTABLISHED";
        const string released = "RELEASED/NOT_ESTABLISHED/NOT_ESTABLISHED/NOT_ESTABLISHED";
        Branch(new [] { "SPAWN_INTENT", "PROCESS_CREATED_SUSPENDED", "PROCESS_ADMISSION_READY", "EXECUTION_RELEASE_INTENT", "EXECUTION_RELEASE_OBSERVED", "EXECUTION_ACTIVITY_OBSERVED", "OUTPUT_SEALED", "EXIT_OBSERVED" },
            new [] { spawned, created, admitted, intent, released, "RELEASED/ESTABLISHED/NOT_ESTABLISHED/NOT_ESTABLISHED", "RELEASED/ESTABLISHED/ESTABLISHED/NOT_ESTABLISHED", "EXITED/ESTABLISHED/ESTABLISHED/ESTABLISHED" });
        Branch(new [] { "SPAWN_INTENT", "PROCESS_CREATED_SUSPENDED", "PROCESS_ADMISSION_READY", "EXECUTION_RELEASE_INTENT", "EXECUTION_RELEASE_OBSERVED", "OUTPUT_SEALED", "EXIT_OBSERVED" },
            new [] { spawned, created, admitted, intent, released, "RELEASED/NOT_ESTABLISHED/ESTABLISHED/NOT_ESTABLISHED", "EXITED/NOT_ESTABLISHED/ESTABLISHED/ESTABLISHED" });
        Branch(new [] { "SPAWN_INTENT", "PROCESS_CREATED_SUSPENDED", "SUSPENDED_TERMINATION_INTENT", "SUSPENDED_TERMINATION_OBSERVED", "OUTPUT_SEALED", "EXIT_OBSERVED" },
            new [] { spawned, created, "SUSPENDED_TERMINATION_INTENT_UNCERTAIN/NOT_ESTABLISHED/NOT_ESTABLISHED/NOT_ESTABLISHED", "TERMINATED_SUSPENDED/NON_EXECUTION_ESTABLISHED/NOT_ESTABLISHED/NOT_ESTABLISHED", "TERMINATED_SUSPENDED/NON_EXECUTION_ESTABLISHED/ESTABLISHED/NOT_ESTABLISHED", "EXITED/NON_EXECUTION_ESTABLISHED/ESTABLISHED/ESTABLISHED" });
        Console.WriteLine("AXIS_VECTORS cases=" + cases + " failures=0");
        return 0;
    }
}
