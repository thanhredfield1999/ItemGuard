using System;
using System.Collections;
using System.IO;
using System.Reflection;
using ItemGuard.Recovery;

class AuthorityGenesisScannerTests {
    static MethodInfo encode;
    static MethodInfo hash;
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }
    static byte[] Line(ulong seq, string previous, string eventName, string token) {
        var record = new Hashtable();
        record["seq"] = seq;
        record["previousRecordSha256"] = previous;
        record["event"] = eventName;
        record["runToken"] = token;
        record["recordSha256"] = hash.Invoke(null, new object[] { record });
        return (byte[])encode.Invoke(null, new object[] { record });
    }
    static string Sha(byte[] bytes) {
        using (var value = System.Security.Cryptography.SHA256.Create()) {
            var text = new System.Text.StringBuilder();
            foreach (byte b in value.ComputeHash(bytes)) text.Append(b.ToString("x2"));
            return text.ToString();
        }
    }
    static byte[] Join(byte[] first, byte[] second) {
        var all = new byte[first.Length + second.Length];
        Buffer.BlockCopy(first, 0, all, 0, first.Length);
        Buffer.BlockCopy(second, 0, all, first.Length, second.Length);
        return all;
    }
    static AuthorityGenesisState Scan(byte[] left, byte[] right) {
        using (var first = new MemoryStream(left, false))
        using (var second = new MemoryStream(right, false))
            return AuthorityGenesisScanner.Scan(first, second, CanonicalDecoder.MaxDocumentBytes * 3);
    }
    static int Main(string[] args) {
        try {
            var guardian = Assembly.LoadFrom(args[0]).GetType("ItemGuard.Execution.CanonicalJson", true);
            encode = guardian.GetMethod("EncodeJournalLine");
            hash = guardian.GetMethod("ComputeRecordSha256");
            const string token = "6d4b2bb8-4a0d-4f7d-95e4-4b9bb886815a";
            byte[] ready = Line(0, new string('0', 64), "AUTHORITY_READY", token);
            byte[] consumed = Line(1, Sha(ready), "AUTHORIZATION_CONSUMED", token);
            Check(Scan(new byte[0], new byte[0]) == AuthorityGenesisState.Empty,
                "empty exact snapshots establish no authority state");
            Check(Scan(ready, (byte[])ready.Clone()) == AuthorityGenesisState.AuthorityReadyOnly,
                "one exact genesis line remains authorization-incomplete");
            byte[] complete = Join(ready, consumed);
            Check(Scan(complete, (byte[])complete.Clone()) == AuthorityGenesisState.AuthorizationConsumed,
                "two exact chained journal lines establish consumed authorization");
            Check(Scan(complete, ready) == AuthorityGenesisState.Invalid,
                "one-sided consumed suffix cannot establish authorization");
            byte[] differentToken = Line(1, Sha(ready), "AUTHORIZATION_CONSUMED", "6d4b2bb8-4a0d-4f7d-95e4-4b9bb886815b");
            Check(Scan(Join(ready, differentToken), Join(ready, differentToken)) == AuthorityGenesisState.Invalid,
                "different run token cannot consume a genesis authorization");
            Console.WriteLine("AUTHORITY_GENESIS_SCANNER checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
    }
}
