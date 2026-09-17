using System;
using System.Collections;
using System.Text;
using ItemGuard.Execution;

class GuardianCanonicalDecoderTests {
    static int checks;
    static readonly UTF8Encoding Utf8 = new UTF8Encoding(false, true);
    static void Check(bool value, string label) { checks++; if (!value) throw new Exception(label); }
    static bool Reject(string document) {
        try { GuardianCanonicalDecoder.ParseDocument(Utf8.GetBytes(document)); return false; }
        catch (FormatException) { return true; }
    }
    static int Main() {
        try {
            IDictionary valid = GuardianCanonicalDecoder.ParseDocument(Utf8.GetBytes("{\"a\":1,\"b\":false}"));
            Check((ulong)valid["a"] == 1 && (bool)valid["b"] == false,
                "guardian decoder accepts canonical receipt-shaped document");
            IDictionary maximum = GuardianCanonicalDecoder.ParseDocument(Utf8.GetBytes("{\"timestamp\":18446744073709551615}"));
            Check((ulong)maximum["timestamp"] == ulong.MaxValue,
                "guardian decoder preserves the full UInt64 authorization timestamp domain");
            Check(Reject("{\"b\":false,\"a\":1}"),
                "guardian decoder rejects out-of-order members before authorization validation");
            Check(Reject("{\"a\":1 }"),
                "guardian decoder rejects whitespace in external receipt bytes");
            Check(Reject("{\"a\":1,\"a\":2}"),
                "guardian decoder rejects duplicate receipt member names");
            Console.WriteLine("GUARDIAN_CANONICAL_DECODER checks=" + checks + " failures=0");
            return 0;
        } catch (Exception exception) {
            Console.WriteLine("FAIL " + exception); return 1;
        }
    }
}
