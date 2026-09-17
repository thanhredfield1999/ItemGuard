using System;
using ItemGuard.Execution;

class NamespaceOneShotPolicyTests {
    static int checks;
    static void Check(bool value, string label) { checks++; if (!value) throw new Exception(label); }
    static int Main() {
        try {
            Check(NamespaceOneShotPolicy.Decide(false, false, false) == NamespaceAdmissionDecision.CreateFirstLeaf,
                "only an entirely absent namespace can begin first-leaf creation");
            Check(NamespaceOneShotPolicy.Decide(true, false, false) == NamespaceAdmissionDecision.BlockConsumedOrPartial,
                "first mirror leaf collision blocks namespace without cleanup or retry");
            Check(NamespaceOneShotPolicy.Decide(false, true, false) == NamespaceAdmissionDecision.BlockConsumedOrPartial,
                "second mirror leaf collision blocks namespace without cleanup or retry");
            Check(NamespaceOneShotPolicy.Decide(false, false, true) == NamespaceAdmissionDecision.BlockConsumedOrPartial,
                "runtime leaf collision blocks namespace without interpreting authorization state");
            Console.WriteLine("NAMESPACE_ONE_SHOT_POLICY checks=" + checks + " failures=0");
            return 0;
        } catch (Exception exception) { Console.WriteLine("FAIL " + exception); return 1; }
    }
}
