using System;
using System.Collections;
namespace ItemGuard.Execution {
    // Pure commit policy only; no disk, native handle, schema or runtime authority.
    // Caller owns a stable record tree until return. Sinks are trusted synchronous adapters.
    // false forbids ACK; it does NOT prove no bytes reached either mirror. Recovery reads both.
    // A sink may block: watchdog/job-close authority must remain independent of this lock.
    public sealed class DualJournalCommitCore {
        readonly Func<byte[], int> appendFirst, appendSecond;
        readonly Action flushFirst, flushSecond;
        readonly int maximumRecords;
        readonly object gate = new object();
        bool committing;
        // Inspect state only on the serial owner or after joining the commit operation.
        public bool Failed { get; private set; }
        public int CommittedCount { get; private set; }
        public string LastCommittedLineSha256 { get; private set; }
        public DualJournalCommitCore(Func<byte[], int> appendFirst, Action flushFirst,
            Func<byte[], int> appendSecond, Action flushSecond, int maximumRecords) {
            if (appendFirst == null) throw new ArgumentNullException("appendFirst");
            if (flushFirst == null) throw new ArgumentNullException("flushFirst");
            if (appendSecond == null) throw new ArgumentNullException("appendSecond");
            if (flushSecond == null) throw new ArgumentNullException("flushSecond");
            this.appendFirst = appendFirst; this.flushFirst = flushFirst;
            this.appendSecond = appendSecond; this.flushSecond = flushSecond;
            if (maximumRecords <= 0) throw new ArgumentOutOfRangeException("maximumRecords");
            this.maximumRecords = maximumRecords;
        }
        public bool TryCommit(IDictionary record) {
            lock (gate) { return CommitLocked(record); }
        }
        bool CommitLocked(IDictionary record) {
            if (Failed) return false;
            if (committing) return Reject();
            committing = true;
            try {
                if (record == null || CommittedCount >= maximumRecords
                    || !(record["seq"] is ulong) || (ulong)record["seq"] != (ulong)CommittedCount
                    || !(record["previousRecordSha256"] is string)
                    || (string)record["previousRecordSha256"] != (LastCommittedLineSha256 ?? new string('0', 64))) return Reject();
                byte[] line = CanonicalJson.EncodeJournalLine(record);
                string lineHash;
                using (var sha = System.Security.Cryptography.SHA256.Create()) {
                    var text = new System.Text.StringBuilder(64);
                    foreach (byte b in sha.ComputeHash(line)) text.Append(b.ToString("x2"));
                    lineHash = text.ToString();
                }
                if (appendFirst((byte[])line.Clone()) != line.Length || Failed) return Reject();
                flushFirst();
                if (Failed) return false;
                if (appendSecond((byte[])line.Clone()) != line.Length || Failed) return Reject();
                flushSecond();
                if (Failed) return false;
                LastCommittedLineSha256 = lineHash;
                CommittedCount++;
                return true;
            } catch (Exception) {
                return Reject();
            } finally {
                committing = false;
            }
        }
        bool Reject() { Failed = true; return false; }
    }
}
