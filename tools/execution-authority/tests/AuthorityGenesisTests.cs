using System;
using System.Collections;
using ItemGuard.Recovery;

class AuthorityGenesisTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }
    static IDictionary Record(ulong seq, string eventName, string token) {
        var value = new Hashtable();
        value["seq"] = seq;
        value["event"] = eventName;
        value["runToken"] = token;
        return value;
    }
    static int Main() {
        try {
            const string token = "6d4b2bb8-4a0d-4f7d-95e4-4b9bb886815a";
            Check(AuthorityGenesis.Classify(new IDictionary[0]) == AuthorityGenesisState.Empty,
                "empty trusted prefix establishes no authority record");
            Check(AuthorityGenesis.Classify(new[] { Record(0, "AUTHORITY_READY", token) })
                == AuthorityGenesisState.AuthorityReadyOnly,
                "authority-ready alone remains an incomplete authorization prefix");
            Check(AuthorityGenesis.Classify(new[] {
                Record(0, "AUTHORITY_READY", token),
                Record(1, "AUTHORIZATION_CONSUMED", token)
            }) == AuthorityGenesisState.AuthorizationConsumed,
                "exact two-record prefix establishes durable authorization consumption");
            foreach (IDictionary[] invalid in new[] {
                new[] { Record(1, "AUTHORITY_READY", token) },
                new[] { Record(0, "WRONG", token) },
                new[] { Record(0, "AUTHORITY_READY", "not-a-token") },
                new[] { Record(0, "AUTHORITY_READY", token), Record(1, "AUTHORIZATION_CONSUMED", "6d4b2bb8-4a0d-4f7d-95e4-4b9bb886815b") },
                new[] { Record(0, "AUTHORITY_READY", token), Record(2, "AUTHORIZATION_CONSUMED", token) },
                new[] { Record(0, "AUTHORITY_READY", token), Record(1, "WRONG", token) },
                new[] { Record(0, "AUTHORITY_READY", token), Record(1, "AUTHORIZATION_CONSUMED", token), Record(2, "EXIT_OBSERVED", token) }
            }) {
                Check(AuthorityGenesis.Classify(invalid) == AuthorityGenesisState.Invalid,
                    "unexpected, malformed, mismatched, or extended genesis prefix is invalid");
            }
            Console.WriteLine("AUTHORITY_GENESIS checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
    }
}
