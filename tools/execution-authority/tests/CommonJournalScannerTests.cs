using System;
using System.Collections;
using System.IO;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using ItemGuard.Recovery;

// Finite in-memory snapshots only; no runtime journal, file handle or receipt is read.
class CommonJournalScannerTests {
    static MethodInfo encode;
    static MethodInfo recordHash;
    static int cases;
    static int failures;
    static void Check(bool value, string message) { if (!value) throw new Exception(message); }
    static void Run(string name, Action test) {
        cases++;
        try { test(); } catch (Exception e) { failures++; Console.WriteLine("FAIL " + name + ": " + e.Message); }
    }
    static string Sha(byte[] bytes) {
        using (var sha = SHA256.Create()) {
            var text = new StringBuilder();
            foreach (byte b in sha.ComputeHash(bytes)) text.Append(b.ToString("x2"));
            return text.ToString();
        }
    }
    static byte[] Line(ulong seq, string previous) {
        var record = new Hashtable();
        record["seq"] = seq; record["previousRecordSha256"] = previous;
        record["recordSha256"] = recordHash.Invoke(null, new object[] { record });
        return (byte[])encode.Invoke(null, new object[] { record });
    }
    static byte[] Join(byte[] a, byte[] b) {
        var bytes = new byte[a.Length + b.Length];
        Buffer.BlockCopy(a, 0, bytes, 0, a.Length);
        Buffer.BlockCopy(b, 0, bytes, a.Length, b.Length);
        return bytes;
    }
    static void CommonTwoLines() {
        byte[] first = Line(0, new string('0', 64));
        byte[] all = Join(first, Line(1, Sha(first)));
        using (var left = new MemoryStream(all, false))
        using (var right = new MemoryStream(all, false)) {
            var result = CommonJournalScanner.Scan(left, right, 10);
            Check(result.CommonRecordCount == 2, "scan must examine both records");
            Check(result.End == JournalScanEnd.BothEof, "both snapshots exhausted without invalid suffix");
            Check(left.CanRead && right.CanRead, "caller owns stream lifetime");
        }
    }
    sealed class ShortReadStream : MemoryStream {
        readonly int chunk;
        readonly int failAt;
        public ShortReadStream(byte[] data, int chunk, int failAt) : base(data, false) {
            this.chunk = chunk; this.failAt = failAt;
        }
        public override int Read(byte[] buffer, int offset, int count) {
            if (failAt >= 0 && Position >= failAt) throw new IOException("injected read failure");
            if (failAt >= 0) count = Math.Min(count, failAt - (int)Position);
            return base.Read(buffer, offset, Math.Min(count, chunk));
        }
    }
    static void Suffixes() {
        byte[] first = Line(0, new string('0', 64)), next = Line(1, Sha(first));
        byte[] all = Join(first, next);
        byte[] partial = (byte[])next.Clone(); Array.Resize(ref partial, partial.Length - 1);
        byte[][] suffixes = { partial, new byte[] { 10 }, Encoding.ASCII.GetBytes("garbage\n"),
            Line(2, Sha(first)), first, new byte[CanonicalDecoder.MaxDocumentBytes] };
        foreach (byte[] suffix in suffixes) {
            using (var left = new MemoryStream(Join(first, suffix), false))
            using (var right = new MemoryStream(Join(first, suffix), false)) {
                var result = CommonJournalScanner.Scan(left, right, 10);
                Check(result.CommonRecordCount == 1 && result.End == JournalScanEnd.InvalidSuffix, "never skip invalid/partial suffix");
            }
        }
        for (int side = 0; side < 2; side++) {
            using (var left = new MemoryStream(side == 0 ? all : first, false))
            using (var right = new MemoryStream(side == 0 ? first : all, false)) {
                var result = CommonJournalScanner.Scan(left, right, 10);
                Check(result.CommonRecordCount == 1 && result.End == JournalScanEnd.InvalidSuffix, "one-sided suffix cannot promote fact");
            }
        }
    }
    static void ShortReads() {
        byte[] first = Line(0, new string('0', 64));
        byte[] all = Join(first, Line(1, Sha(first)));
        foreach (int chunk in new [] { 1, 2, 7, 31, 4096 }) {
            using (var left = new ShortReadStream(all, chunk, -1))
            using (var right = new ShortReadStream(all, 3, -1)) {
                var result = CommonJournalScanner.Scan(left, right, 10);
                Check(result.CommonRecordCount == 2 && result.End == JournalScanEnd.BothEof, "different chunk boundaries do not change evidence");
            }
        }
    }
    static void ReadFailures() {
        byte[] first = Line(0, new string('0', 64));
        byte[] all = Join(first, Line(1, Sha(first)));
        foreach (int failAt in new [] { 0, 1, first.Length - 1, first.Length, first.Length + 5 }) {
            for (int side = 0; side < 2; side++) {
                using (var left = new ShortReadStream(all, 17, side == 0 ? failAt : -1))
                using (var right = new ShortReadStream(all, 13, side == 1 ? failAt : -1)) {
                    var result = CommonJournalScanner.Scan(left, right, 10);
                    Check(result.CommonRecordCount == (failAt >= first.Length ? 1 : 0), "read failure retains only complete common facts");
                    Check(result.End == JournalScanEnd.ReadFailure, "read error is not EOF");
                }
            }
        }
    }
    static void Limits() {
        byte[] first = Line(0, new string('0', 64));
        byte[] all = Join(first, Line(1, Sha(first)));
        using (var left = new MemoryStream(first, false))
        using (var right = new MemoryStream(first, false)) {
            var result = CommonJournalScanner.Scan(left, right, 1);
            Check(result.CommonRecordCount == 1 && result.End == JournalScanEnd.BothEof, "exact record budget still checks EOF");
        }
        using (var left = new MemoryStream(all, false))
        using (var right = new MemoryStream(first, false)) {
            var result = CommonJournalScanner.Scan(left, right, 1);
            Check(result.CommonRecordCount == 1 && result.End == JournalScanEnd.RecordLimit, "do not misclassify uninspected suffix or claim EOF at budget");
        }
    }
    static void InputGuards() {
        foreach (string scenario in new [] { "same", "nonzero-left", "nonzero-right", "null-left", "null-right", "closed-left", "closed-right", "zero-limit" }) {
            using (var left = new MemoryStream(new byte[] { 10 }, false))
            using (var right = new MemoryStream(new byte[] { 10 }, false)) {
                if (scenario == "nonzero-left") left.Position = 1;
                if (scenario == "nonzero-right") right.Position = 1;
                if (scenario == "closed-left") left.Dispose();
                if (scenario == "closed-right") right.Dispose();
                bool rejected = false;
                try {
                    CommonJournalScanner.Scan(scenario == "null-left" ? null : left,
                        scenario == "null-right" ? null : scenario == "same" ? left : right,
                        scenario == "zero-limit" ? 0 : 1);
                } catch (ArgumentException) { rejected = true; }
                Check(rejected, "reject invalid snapshot input: " + scenario);
            }
        }
    }
    static void TruncationMatrix() {
        byte[] first = Line(0, new string('0', 64));
        byte[] all = Join(first, Line(1, Sha(first)));
        int probes = 0;
        for (int length = 0; length <= all.Length; length++) {
            byte[] prefix = new byte[length]; Buffer.BlockCopy(all, 0, prefix, 0, length);
            for (int side = 0; side < 3; side++) {
                using (var left = new MemoryStream(side == 1 ? all : prefix, false))
                using (var right = new MemoryStream(side == 0 ? all : prefix, false)) {
                    var result = CommonJournalScanner.Scan(left, right, 10);
                    int expectedCount = length == all.Length ? 2 : length >= first.Length ? 1 : 0;
                    bool bothEnd = length == all.Length || (side == 2 && (length == 0 || length == first.Length));
                    Check(result.CommonRecordCount == expectedCount, "cut retains exact common prefix count");
                    Check(result.End == (bothEnd ? JournalScanEnd.BothEof : JournalScanEnd.InvalidSuffix), "cut bytes must not imply complete history");
                    probes++;
                }
            }
        }
        Console.WriteLine("JOURNAL_TRUNCATIONS cases=" + probes + " failures=0");
    }
    static void DoesNotSkipBadMiddle() {
        byte[] first = Line(0, new string('0', 64));
        byte[] all = Join(Join(first, Encoding.ASCII.GetBytes("bad\n")), Line(1, Sha(first)));
        using (var left = new MemoryStream(all, false)) using (var right = new MemoryStream(all, false)) {
            var result = CommonJournalScanner.Scan(left, right, 10);
            Check(result.CommonRecordCount == 1 && result.End == JournalScanEnd.InvalidSuffix, "valid records after corruption never resume scan");
        }
    }
    static int Main(string[] args) {
        try {
            var type = Assembly.LoadFrom(args[0]).GetType("ItemGuard.Execution.CanonicalJson", true);
            encode = type.GetMethod("EncodeJournalLine");
            recordHash = type.GetMethod("ComputeRecordSha256");
            Run("both full mirrors", CommonTwoLines);
            Run("invalid and one-sided suffixes", Suffixes);
            Run("arbitrary short reads", ShortReads);
            Run("read errors retain prefix", ReadFailures);
            Run("record budget", Limits);
            Run("snapshot input guards", InputGuards);
            Run("every truncation point on either or both mirrors", TruncationMatrix);
            Run("never skip corrupt middle", DoesNotSkipBadMiddle);
            Run("empty snapshots are not genesis evidence", delegate {
                using (var left = new MemoryStream()) using (var right = new MemoryStream()) {
                    var result = CommonJournalScanner.Scan(left, right, 1);
                    Check(result.CommonRecordCount == 0 && result.End == JournalScanEnd.BothEof, "empty byte streams only");
                }
            });
            Console.WriteLine("JOURNAL_SCAN cases=" + cases + " failures=" + failures);
            return failures == 0 ? 0 : 1;
        } catch (Exception e) { Console.WriteLine("FAIL bootstrap " + e); return 1; }
    }
}
