using System;
using System.Collections;
using System.Collections.Generic;
using System.Text;
using ItemGuard.Execution;

class DualJournalCommitTests {
    static int checks;
    static void Check(bool ok, string label) { checks++; if (!ok) throw new Exception(label); }
    static IDictionary Record(ulong seq, string previous) {
        var body = new Hashtable(); body["seq"] = seq; body["previousRecordSha256"] = previous;
        body["event"] = "TEST_ONLY";
        body["recordSha256"] = CanonicalJson.ComputeRecordSha256(body);
        return body;
    }
    static string Hash(byte[] bytes) {
        using (var hash = System.Security.Cryptography.SHA256.Create()) {
            var text = new StringBuilder(); foreach (byte b in hash.ComputeHash(bytes)) text.Append(b.ToString("x2"));
            return text.ToString();
        }
    }
    static int Main() {
        try {
            var order = new List<string>(); byte[] first = null, second = null;
            var writer = new DualJournalCommitCore(
                bytes => { order.Add("E.write"); first = bytes; return bytes.Length; }, () => order.Add("E.flush"),
                bytes => { order.Add("C.write"); second = bytes; return bytes.Length; }, () => order.Add("C.flush"), 2);
            var record = Record(0, new string('0', 64));
            Check(writer.TryCommit(record), "both append and flush must permit commit");
            Check(String.Join(",", order) == "E.write,E.flush,C.write,C.flush", "exact dual commit order");
            Check(Convert.ToBase64String(first) == Convert.ToBase64String(second), "same exact bytes");
            Check(!Object.ReferenceEquals(first, second), "sinks cannot alias line arrays");
            Check(writer.CommittedCount == 1 && !writer.Failed, "commit count");
            Check(writer.LastCommittedLineSha256 == Hash(first), "hash complete LF-terminated line");
            var recovery = new ItemGuard.Recovery.CommonJournalChain(2);
            Check(recovery.AppendPair(first, second), "independent recovery accepts committed bytes");
            Check(writer.TryCommit(Record(1, writer.LastCommittedLineSha256)), "second chained commit");
            Check(writer.CommittedCount == 2, "second count");
            Check(recovery.AppendPair(first, second) && recovery.CommonRecordCount == 2, "independent recovery accepts chained bytes");
            for (int point = 0; point < 6; point++) {
                int failAt = point, calls = 0;
                var broken = new DualJournalCommitCore(
                    bytes => { calls++; if (failAt == 0) throw new System.IO.IOException("E.write"); return failAt == 4 ? bytes.Length - 1 : bytes.Length; },
                    () => { calls++; if (failAt == 1) throw new System.IO.IOException("E.flush"); },
                    bytes => { calls++; if (failAt == 2) throw new System.IO.IOException("C.write"); return failAt == 5 ? bytes.Length - 1 : bytes.Length; },
                    () => { calls++; if (failAt == 3) throw new System.IO.IOException("C.flush"); }, 2);
                bool result = true;
                try { result = broken.TryCommit(Record(0, new string('0', 64))); }
                catch (Exception) { Check(false, "sink failure must poison without throwing"); }
                Check(!result && broken.Failed && broken.CommittedCount == 0, "no commit on partial mirror");
                int stoppedAt = calls;
                Check(!broken.TryCommit(Record(0, new string('0', 64))) && calls == stoppedAt, "poison prevents all retries");
                Check(calls == (point == 4 ? 1 : point == 5 ? 3 : point + 1), "stop at exact failed stage");
            }
            foreach (string defect in new string[]{"seq", "previous", "hash", "missing", "null", "duplicate", "limit"}) {
                int writes = 0;
                var invalid = new DualJournalCommitCore(bytes => { writes++; return bytes.Length; }, () => {}, bytes => { writes++; return bytes.Length; }, () => {}, defect == "duplicate" ? 2 : 1);
                var body = Record(0, new string('0', 64));
                if (defect == "seq") body["seq"] = 1UL;
                if (defect == "previous") body["previousRecordSha256"] = new string('1', 64);
                if (defect == "missing") body.Remove("seq");
                if (defect != "hash") body["recordSha256"] = CanonicalJson.ComputeRecordSha256(body);
                else body["recordSha256"] = new string('f', 64);
                if (defect == "duplicate" || defect == "limit") {
                    Check(invalid.TryCommit(body), "first commit before invalid suffix");
                    if (defect == "limit") body = Record(1, invalid.LastCommittedLineSha256);
                }
                int before = writes;
                Check(!invalid.TryCommit(defect == "null" ? null : body), "invalid chain rejected before append: " + defect);
                Check(invalid.Failed && writes == before, "invalid chain terminal with no writes");
            }
            int reentrantCalls = 0; bool inside = false, nested = true;
            DualJournalCommitCore reentrant = null;
            reentrant = new DualJournalCommitCore(bytes => {
                reentrantCalls++;
                if (!inside) { inside = true; nested = reentrant.TryCommit(Record(0, new string('0', 64))); }
                return bytes.Length;
            }, () => { reentrantCalls++; }, bytes => { reentrantCalls++; return bytes.Length; }, () => { reentrantCalls++; }, 2);
            Check(!reentrant.TryCommit(Record(0, new string('0', 64))) && !nested, "reentrant callback must fail both commits");
            Check(reentrant.Failed && reentrant.CommittedCount == 0 && reentrantCalls == 1, "reentrant call poisons before next sink action");
            var entered = new System.Threading.ManualResetEventSlim(false);
            var release = new System.Threading.ManualResetEventSlim(false);
            var started = new System.Threading.ManualResetEventSlim(false);
            var finished = new System.Threading.ManualResetEventSlim(false);
            int serialWrites = 0; bool a = false, b = false;
            var serial = new DualJournalCommitCore(bytes => {
                if (System.Threading.Interlocked.Increment(ref serialWrites) == 1) {
                    entered.Set(); if (!release.Wait(3000)) throw new Exception("test release timeout");
                }
                return bytes.Length;
            }, () => {}, bytes => bytes.Length, () => {}, 2);
            var record0 = Record(0, new string('0', 64));
            var record1 = Record(1, Hash(CanonicalJson.EncodeJournalLine(record0)));
            var threadA = new System.Threading.Thread(() => { a = serial.TryCommit(record0); });
            var threadB = new System.Threading.Thread(() => { started.Set(); b = serial.TryCommit(record1); finished.Set(); });
            threadA.Start(); Check(entered.Wait(3000), "first serial sink entered"); threadB.Start();
            Check(started.Wait(3000), "second serial request started");
            bool early = finished.Wait(150);
            release.Set(); Check(threadA.Join(3000) && threadB.Join(3000), "serial threads finish");
            Check(!early && a && b && serial.CommittedCount == 2 && !serial.Failed, "concurrent commits must serialize, not poison");
            byte[] clean = CanonicalJson.EncodeJournalLine(Record(0, new string('0', 64)));
            byte[] sinkTwo = null;
            var mutation = new DualJournalCommitCore(bytes => { bytes[0] ^= 1; return bytes.Length; }, () => {},
                bytes => { sinkTwo = bytes; return bytes.Length; }, () => {}, 1);
            Check(mutation.TryCommit(Record(0, new string('0', 64))), "sink return contract only, no durability attestation");
            Check(Convert.ToBase64String(clean) == Convert.ToBase64String(sinkTwo) && mutation.LastCommittedLineSha256 == Hash(clean), "first sink cannot mutate second bytes or retained hash");
            for (int missing = 0; missing < 4; missing++) {
                bool rejected = false;
                Func<byte[], int> append = bytes => bytes.Length; Action flush = () => {};
                try { new DualJournalCommitCore(missing == 0 ? null : append, missing == 1 ? null : flush,
                    missing == 2 ? null : append, missing == 3 ? null : flush, 1); }
                catch (ArgumentNullException) { rejected = true; }
                Check(rejected, "missing sink must reject at construction");
            }
            bool failNext = false;
            var suffix = new DualJournalCommitCore(bytes => bytes.Length, () => {}, bytes => bytes.Length,
                () => { if (failNext) throw new System.IO.IOException("last flush"); }, 3);
            Check(suffix.TryCommit(Record(0, new string('0', 64))), "prefix committed before failed suffix");
            string prefixHash = suffix.LastCommittedLineSha256; failNext = true;
            Check(!suffix.TryCommit(Record(1, prefixHash)), "suffix flush failure returns no new commit");
            Check(suffix.Failed && suffix.CommittedCount == 1 && suffix.LastCommittedLineSha256 == prefixHash, "failed suffix preserves committed prefix state");
            Check(CanonicalJson.MaxDocumentBytes == ItemGuard.Recovery.CanonicalDecoder.MaxDocumentBytes, "independent codec line caps match");
            Console.WriteLine("DUAL_COMMIT checks=" + checks + " failures=0 (pure sinks only)"); return 0;
        } catch (Exception ex) { Console.WriteLine("FAIL " + ex.Message); return 1; }
    }
}
