using System;
using System.Collections;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using ItemGuard.Recovery;

// Minimal chain documents, not full runtime journal schemas or authority receipts.
class CommonJournalChainTests {
    static MethodInfo encode;
    static MethodInfo recordHash;
    static int cases;
    static int failures;
    static void Check(bool value, string message) { if (!value) throw new Exception(message); }
    static void Run(string name, Action test) {
        cases++;
        try { test(); }
        catch (Exception e) { failures++; Console.WriteLine("FAIL " + name + ": " + e.Message); }
    }
    static string Sha(byte[] bytes) {
        using (var sha = SHA256.Create()) {
            var text = new StringBuilder();
            foreach (byte b in sha.ComputeHash(bytes)) text.Append(b.ToString("x2"));
            return text.ToString();
        }
    }
    static byte[] Line(ulong seq, string previous, string note) {
        var doc = new Hashtable();
        doc["seq"] = seq; doc["previousRecordSha256"] = previous; doc["note"] = note;
        doc["recordSha256"] = recordHash.Invoke(null, new object[] { doc });
        return (byte[])encode.Invoke(null, new object[] { doc });
    }
    static void CommonPrefix() {
        var chain = new CommonJournalChain(10);
        byte[] first = Line(0, new string('0', 64), "genesis");
        byte[] next = Line(1, Sha(first), "next");
        Check(chain.AppendPair(first, (byte[])first.Clone()), "first common record");
        Check(chain.CommonRecordCount == 1, "count one");
        Check(chain.AppendPair(next, (byte[])next.Clone()), "next binds exact complete prior line including LF");
        Check(chain.CommonRecordCount == 2 && !chain.Stopped, "two common chain records");
    }
    static void RejectSuffix(string scenario) {
        var chain = new CommonJournalChain(10);
        byte[] first = Line(0, new string('0', 64), "genesis");
        Check(chain.AppendPair(first, first), "valid genesis");
        byte[] next = Line(1, Sha(first), "next");
        byte[] left = (byte[])next.Clone(), right = (byte[])next.Clone();
        switch (scenario) {
            case "missing-left": left = null; break;
            case "missing-right": right = null; break;
            case "missing-both": left = right = null; break;
            case "truncated": Array.Resize(ref left, left.Length - 1); right = (byte[])left.Clone(); break;
            case "one-sided-truncation": Array.Resize(ref right, right.Length - 1); break;
            case "gap": left = right = Line(2, Sha(first), "gap"); break;
            case "repeat": left = right = first; break;
            case "wrong-previous": left = right = Line(1, new string('0', 64), "wrong"); break;
            case "previous-excludes-LF":
                var withoutLf = new byte[first.Length - 1];
                Buffer.BlockCopy(first, 0, withoutLf, 0, withoutLf.Length);
                left = right = Line(1, Sha(withoutLf), "wrong hash boundary"); break;
            case "different-valid-records": right = Line(1, Sha(first), "else"); break;
            case "corrupt-both": left[10] ^= 1; right = (byte[])left.Clone(); break;
            case "oversized": left = right = new byte[1048577]; break;
            case "double-LF": Array.Resize(ref left, left.Length + 1); left[left.Length - 1] = 10; right = left; break;
            default: throw new Exception("unknown fixture");
        }
        Check(!chain.AppendPair(left, right) && chain.Stopped, "bad suffix stops");
        Check(chain.CommonRecordCount == 1, "prefix fact retained");
        Check(!chain.AppendPair(next, next) && chain.CommonRecordCount == 1, "cannot skip bad record and resume");
    }
    static void MalformedChainField(string field, object value) {
        var doc = new Hashtable();
        doc["seq"] = (ulong)0; doc["previousRecordSha256"] = new string('0', 64);
        if (value == null) doc.Remove(field); else doc[field] = value;
        doc["recordSha256"] = recordHash.Invoke(null, new object[] { doc });
        byte[] bytes = (byte[])encode.Invoke(null, new object[] { doc });
        var chain = new CommonJournalChain(10);
        Check(!chain.AppendPair(bytes, bytes) && chain.Stopped && chain.CommonRecordCount == 0, "valid record hash does not excuse invalid chain field");
    }
    static void RecordBudget() {
        var chain = new CommonJournalChain(1);
        byte[] first = Line(0, new string('0', 64), "genesis");
        byte[] next = Line(1, Sha(first), "next");
        Check(chain.AppendPair(first, first), "within budget");
        Check(!chain.AppendPair(next, next) && chain.Stopped && chain.CommonRecordCount == 1, "over budget retains prefix");
    }
    static void InputAlias() {
        var chain = new CommonJournalChain(10);
        byte[] first = Line(0, new string('0', 64), "genesis");
        byte[] next = Line(1, Sha(first), "next");
        Check(chain.AppendPair(first, first), "first");
        Array.Clear(first, 0, first.Length);
        Check(chain.AppendPair(next, next), "later caller mutation must not change prior-line binding");
    }
    static void RejectMalformedBytes() {
        byte[] valid = Line(0, new string('0', 64), "genesis");
        byte[][] malformed = {
            new byte[0], new byte[] { 10 },
            Encoding.ASCII.GetBytes("{\"seq\":18446744073709551616}\n"),
            new byte[] { 123, 34, 110, 34, 58, 34, 0xc0, 0x80, 34, 125, 10 },
            new byte[] { 123, 34, 110, 34, 58, 34, 0xed, 0xa0, 0x80, 34, 125, 10 }
        };
        foreach (byte[] bytes in malformed) {
            var chain = new CommonJournalChain(10);
            Check(!chain.AppendPair(bytes, bytes) && chain.Stopped, "malformed bytes stop without exception escape");
            Check(!chain.AppendPair(valid, valid) && chain.CommonRecordCount == 0, "cannot resume after malformed bytes");
        }
    }
    static void MutationMatrix() {
        byte[] valid = Line(0, new string('0', 64), "genesis");
        int mutations = 0;
        for (int position = 0; position < valid.Length; position++) {
            for (int value = 0; value <= 255; value++) {
                if (valid[position] == value) continue;
                byte[] bytes = (byte[])valid.Clone(); bytes[position] = (byte)value;
                var chain = new CommonJournalChain(10);
                Check(!chain.AppendPair(bytes, bytes) && chain.Stopped && chain.CommonRecordCount == 0,
                    "single-byte mutation accepted or exception escaped at " + position + ":" + value);
                mutations++;
            }
        }
        Console.WriteLine("CHAIN_BYTE_MUTATIONS cases=" + mutations + " failures=0");
    }
    static int Main(string[] args) {
        try {
            var type = Assembly.LoadFrom(args[0]).GetType("ItemGuard.Execution.CanonicalJson", true);
            encode = type.GetMethod("EncodeJournalLine");
            recordHash = type.GetMethod("ComputeRecordSha256");
            Run("two common records with independent codecs", CommonPrefix);
            foreach (string scenario in new [] { "missing-left", "missing-right", "missing-both", "truncated", "one-sided-truncation", "gap", "repeat", "wrong-previous", "previous-excludes-LF", "different-valid-records", "corrupt-both", "oversized", "double-LF" }) {
                string name = scenario;
                Run(name, delegate { RejectSuffix(name); });
            }
            Run("missing seq", delegate { MalformedChainField("seq", null); });
            Run("string seq", delegate { MalformedChainField("seq", "0"); });
            Run("nonzero genesis seq", delegate { MalformedChainField("seq", (ulong)1); });
            Run("missing previous hash", delegate { MalformedChainField("previousRecordSha256", null); });
            Run("numeric previous hash", delegate { MalformedChainField("previousRecordSha256", (ulong)0); });
            Run("wrong genesis seed", delegate { MalformedChainField("previousRecordSha256", new string('a', 64)); });
            Run("record budget", RecordBudget);
            Run("caller array mutation", InputAlias);
            Run("malformed bytes exception boundary", RejectMalformedBytes);
            Run("single byte mutation matrix", MutationMatrix);
            Run("invalid limit", delegate {
                bool rejected = false;
                try { new CommonJournalChain(0); } catch (ArgumentOutOfRangeException) { rejected = true; }
                Check(rejected, "positive budget required");
            });
            Console.WriteLine("CHAIN cases=" + cases + " failures=" + failures);
            return failures == 0 ? 0 : 1;
        } catch (Exception e) { Console.WriteLine("FAIL bootstrap " + e); return 1; }
    }
}
