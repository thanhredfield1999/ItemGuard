using System;
using System.Collections;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using ItemGuard.Execution;
using ItemGuard.Recovery;

class NativeDualJournalWriterTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }
    static void Expect<T>(Action action, string label) where T : Exception {
        bool rejected = false;
        try { action(); } catch (T) { rejected = true; }
        Check(rejected, label);
    }
    static IDictionary Record(ulong seq, string previous) {
        var body = new Hashtable();
        body["seq"] = seq;
        body["previousRecordSha256"] = previous;
        body["event"] = "TEST_ONLY";
        body["recordSha256"] = CanonicalJson.ComputeRecordSha256(body);
        return body;
    }
    static string Hash(byte[] bytes) {
        using (var hash = SHA256.Create()) {
            var text = new StringBuilder();
            foreach (byte b in hash.ComputeHash(bytes)) text.Append(b.ToString("x2"));
            return text.ToString();
        }
    }
    static byte[][] Lines(byte[] bytes) {
        var lines = new System.Collections.Generic.List<byte[]>();
        int start = 0;
        for (int i = 0; i < bytes.Length; i++) {
            if (bytes[i] != 10) continue;
            int length = i - start + 1;
            var line = new byte[length];
            Buffer.BlockCopy(bytes, start, line, 0, length);
            lines.Add(line);
            start = i + 1;
        }
        if (start != bytes.Length) throw new Exception("journal is not LF terminated");
        return lines.ToArray();
    }
    static bool SameBytes(byte[] first, byte[] second) {
        if (first.Length != second.Length) return false;
        for (int i = 0; i < first.Length; i++) if (first[i] != second[i]) return false;
        return true;
    }
    static int Main() {
        string id = Guid.NewGuid().ToString("N");
        string eRoot = "E:/AI.WORK/scratch-execution-authority-native-dual-e-" + id;
        string cRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Temp", "scratch-execution-authority-native-dual-c-" + id);
        string leaf = "authority.jsonl";
        try {
            Directory.CreateDirectory(eRoot);
            Directory.CreateDirectory(cRoot);
            Expect<ArgumentOutOfRangeException>(() => NativeDualJournalWriter.Create(eRoot, cRoot, leaf, 0), "invalid record budget rejects before journal creation");
            Check(!File.Exists(Path.Combine(eRoot, leaf)) && !File.Exists(Path.Combine(cRoot, leaf)), "invalid budget leaves both mirrors absent");
            string secondHash;
            using (var writer = NativeDualJournalWriter.Create(eRoot, cRoot, leaf, 2)) {
                Check(writer.TryCommit(Record(0, new string('0', 64))), "first durable mirror commit");
                Check(writer.TryCommit(Record(1, writer.LastCommittedLineSha256)), "second chained durable mirror commit");
                secondHash = writer.LastCommittedLineSha256;
                Check(writer.CommittedCount == 2 && !writer.Failed, "two commits with no adapter failure");
            }
            byte[] eBytes = File.ReadAllBytes(Path.Combine(eRoot, leaf));
            byte[] cBytes = File.ReadAllBytes(Path.Combine(cRoot, leaf));
            Check(SameBytes(eBytes, cBytes), "both native mirrors contain byte-identical journal stream");
            byte[][] eLines = Lines(eBytes), cLines = Lines(cBytes);
            Check(eLines.Length == 2 && cLines.Length == 2, "both native streams contain two complete lines");
            var recovery = new CommonJournalChain(2);
            Check(recovery.AppendPair(eLines[0], cLines[0]), "independent recovery accepts first native pair");
            Check(recovery.AppendPair(eLines[1], cLines[1]) && recovery.CommonRecordCount == 2, "independent recovery accepts second native pair");
            Check(Hash(eLines[1]) == secondHash, "adapter retains exact second line hash");
            Console.WriteLine("NATIVE_DUAL_JOURNAL checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        } finally {
            try {
                if (Directory.Exists(eRoot)) Directory.Delete(eRoot, true);
                if (Directory.Exists(cRoot)) Directory.Delete(cRoot, true);
            } catch (Exception ex) {
                Console.Error.WriteLine("CLEANUP_FAIL " + ex.Message);
            }
        }
    }
}
