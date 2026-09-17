using System;
using ItemGuard.Execution;
class HistoryCompletenessTests {
    static int Main() {
        int failures = 0;
        for (int mask = 0; mask < 128; mask++) {
            bool actual = HistoryCompleteness.IsComplete((mask&1)!=0,(mask&2)!=0,(mask&4)!=0,(mask&8)!=0,(mask&16)!=0,(mask&32)!=0,(mask&64)!=0);
            if (actual != (mask == 127)) { failures++; Console.WriteLine("FAIL metadata mask="+mask); }
        }
        Console.WriteLine("history combinations=128 failures="+failures);
        return failures == 0 ? 0 : 1;
    }
}
