using System;
using ItemGuard.Execution;
class PaperBranchTests {
    static int failures;
    static void Check(bool value, string label) { if (!value) { failures++; Console.WriteLine("FAIL " + label); } }
    static PaperLifecycle Created() { var s = new PaperLifecycle(); s.Apply("SPAWN_INTENT"); s.Apply("PROCESS_CREATED_SUSPENDED"); return s; }
    static PaperLifecycle Admitted() { var s = Created(); s.Apply("PROCESS_ADMISSION_READY"); return s; }
    static int Main() {
        var s = Admitted();
        Check(s.Apply("EXECUTION_RELEASE_INTENT") && s.Process == "RELEASE_INTENT_UNCERTAIN", "release intent");
        Check(s.Apply("EXECUTION_RELEASE_OBSERVED") && s.Process == "RELEASED" && s.Code == "NOT_ESTABLISHED", "resume not activity");
        Check(s.Apply("EXECUTION_ACTIVITY_OBSERVED") && s.Code == "ESTABLISHED", "activity");
        Check(s.Apply("OUTPUT_SEALED") && s.Output == "ESTABLISHED", "seal");
        Check(s.Apply("EXIT_OBSERVED") && s.Process == "EXITED" && s.Exit == "ESTABLISHED", "exit");
        var t = Created();
        Check(t.Apply("SUSPENDED_TERMINATION_INTENT") && t.Process == "SUSPENDED_TERMINATION_INTENT_UNCERTAIN", "termination intent");
        Check(t.Apply("SUSPENDED_TERMINATION_OBSERVED") && t.Process == "TERMINATED_SUSPENDED" && t.Code == "NON_EXECUTION_ESTABLISHED", "termination observed");
        Check(t.Apply("OUTPUT_SEALED") && t.Apply("EXIT_OBSERVED") && t.Code == "NON_EXECUTION_ESTABLISHED", "abort exit");
        // Externally validated seal with no factual activity (design 776-781).
        // Release permission is not evidence that code executed OR did not execute.
        var noActivity = Admitted();
        noActivity.Apply("EXECUTION_RELEASE_INTENT");
        noActivity.Apply("EXECUTION_RELEASE_OBSERVED");
        Check(noActivity.Apply("OUTPUT_SEALED") && noActivity.Output == "ESTABLISHED"
            && noActivity.Code == "NOT_ESTABLISHED", "zero-activity seal retains uncertainty");
        Check(noActivity.Apply("EXIT_OBSERVED") && noActivity.Exit == "ESTABLISHED"
            && noActivity.Code == "NOT_ESTABLISHED", "zero-activity exit retains uncertainty");
        Console.WriteLine("branch failures=" + failures); return failures == 0 ? 0 : 1;
    }
}
