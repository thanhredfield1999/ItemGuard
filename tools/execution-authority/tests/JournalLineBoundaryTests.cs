using System;
using System.Collections;
using System.IO;
using System.Reflection;
using ItemGuard.Recovery;

class JournalLineBoundaryTests {
    static MethodInfo encodeDocument;
    static MethodInfo recordHash;
    static byte[] LineWithBodyLength(int length) {
        var doc = new Hashtable();
        doc["note"] = "";
        doc["previousRecordSha256"] = new string('0', 64);
        doc["seq"] = (ulong)0;
        doc["recordSha256"] = new string('0', 64);
        byte[] empty = (byte[])encodeDocument.Invoke(null, new object[] { doc });
        doc["note"] = new string('x', length - empty.Length);
        doc["recordSha256"] = recordHash.Invoke(null, new object[] { doc });
        byte[] body = (byte[])encodeDocument.Invoke(null, new object[] { doc });
        Check(body.Length == length, "fixture body size");
        byte[] line = new byte[body.Length + 1];
        Buffer.BlockCopy(body, 0, line, 0, body.Length);
        line[line.Length - 1] = 10;
        return line;
    }
    static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
    static int Main(string[] args) {
        try {
            Type type = Assembly.LoadFrom(args[0]).GetType("ItemGuard.Execution.CanonicalJson", true);
            encodeDocument = type.GetMethod("EncodeDocument", new Type[] { typeof(IDictionary) });
            recordHash = type.GetMethod("ComputeRecordSha256");
            int cap = CanonicalDecoder.MaxDocumentBytes;
            byte[] atCap = LineWithBodyLength(cap - 1);
            Check(CanonicalDecoder.VerifyJournalLine(atCap) != null, "exact line cap accepted by decoder");
            Check(new CommonJournalChain(1).AppendPair(atCap, atCap), "exact line cap accepted by chain");
            using (var left = new MemoryStream(atCap, false)) using (var right = new MemoryStream(atCap, false)) {
                var result = CommonJournalScanner.Scan(left, right, 1);
                Check(result.CommonRecordCount == 1 && result.End == JournalScanEnd.BothEof, "exact line cap including LF accepted by scanner");
            }
            MethodInfo encodeLine = type.GetMethod("EncodeJournalLine");
            byte[] roundTrip = (byte[])encodeLine.Invoke(null, new object[] { CanonicalDecoder.VerifyJournalLine(atCap) });
            Check(roundTrip.Length == atCap.Length, "independent writer has same exact line cap");
            for (int i = 0; i < atCap.Length; i++) Check(roundTrip[i] == atCap[i], "boundary round-trip bytes");
            byte[] overCap = LineWithBodyLength(cap);
            bool rejected = false;
            try { CanonicalDecoder.VerifyJournalLine(overCap); } catch (FormatException) { rejected = true; }
            Check(rejected, "line cap includes LF: decoder must reject max+1");
            var chain = new CommonJournalChain(1);
            Check(!chain.AppendPair(overCap, overCap) && chain.Stopped, "over cap stops chain");
            using (var left = new MemoryStream(overCap, false)) using (var right = new MemoryStream(overCap, false)) {
                var result = CommonJournalScanner.Scan(left, right, 1);
                Check(result.CommonRecordCount == 0 && result.End == JournalScanEnd.InvalidSuffix, "scanner must not allocate an oversized line");
            }
            var overBody = new byte[overCap.Length - 1];
            Buffer.BlockCopy(overCap, 0, overBody, 0, overBody.Length);
            rejected = false;
            try { encodeLine.Invoke(null, new object[] { CanonicalDecoder.ParseDocument(overBody) }); }
            catch (TargetInvocationException e) { if (e.InnerException is ArgumentException) rejected = true; else throw; }
            Check(rejected, "independent writer rejects body+LF beyond cap");
            Console.WriteLine("JOURNAL_LINE_BOUNDS cases=8 failures=0");
            return 0;
        } catch (Exception e) { Console.WriteLine("FAIL JournalLineBoundaryTests: " + e.Message); return 1; }
    }
}
