using System;
using ItemGuard.Execution;

class NativeWorkerSecurityPolicyTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }

    static int Main() {
        try {
            const string sid = "S-1-5-21-100-200-300-400";
            string process = NativeWorkerSecurityPolicy.BuildProcessSddl(sid);
            string thread = NativeWorkerSecurityPolicy.BuildThreadSddl(sid);
            Check(process.StartsWith("D:P("), "process DACL is protected at creation");
            Check(thread.StartsWith("D:P("), "thread DACL is protected at creation");
            Check(process.Contains("D;;0x000c0aeb;;;" + sid), "process DACL denies exactly the sensitive process rights to the worker SID");
            Check(thread.Contains("D;;0x000c0033;;;" + sid), "thread DACL denies exactly the sensitive thread rights to the worker SID");
            Check(process.EndsWith("(A;;GR;;;" + sid + ")"), "process policy permits only generic read after explicit denials");
            Check(thread.EndsWith("(A;;GR;;;" + sid + ")"), "thread policy permits only generic read after explicit denials");
            Check(!process.Contains("GA") && !thread.Contains("GA"), "worker security policy never grants generic all");
            Check(!process.Contains("WD") && !thread.Contains("WD"), "worker security policy never grants world access");
            bool rejected = false;
            try { NativeWorkerSecurityPolicy.BuildProcessSddl("S-1-5-21-1)(A;;GA;;;WD"); }
            catch (ArgumentException) { rejected = true; }
            Check(rejected, "malformed SID cannot inject an SDDL ACE");
            Console.WriteLine("NATIVE_WORKER_SECURITY_POLICY checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
    }
}
