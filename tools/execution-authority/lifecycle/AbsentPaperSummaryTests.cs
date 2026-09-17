using System;
using System.Reflection;
class AbsentPaperSummaryTests {
    static int Main(string[] args) {
        var method = Assembly.LoadFrom(args[0]).GetType("ItemGuard.Execution.AbsentPaperSummary", true).GetMethod("Derive");
        string[] expected = { "PAPER_INTENT_ABSENT_ENFORCEMENT_UNPROVEN", "PAPER_INTENT_ABSENT_ENFORCEMENT_UNPROVEN", "PAPER_INTENT_ABSENT_HISTORY_INCOMPLETE", "FAIL_BEFORE_PAPER" };
        int failures = 0;
        for (int i = 0; i < 4; i++) {
            string actual = (string)method.Invoke(null, new object[] { i >= 2, (i % 2) == 1 });
            if (actual != expected[i]) { Console.WriteLine("FAIL case " + i + ": " + actual + " expected " + expected[i]); failures++; }
        }
        Console.WriteLine(failures == 0 ? "PASS 4 absent-Paper summary cases" : "FAILURES=" + failures);
        return failures == 0 ? 0 : 1;
    }
}
