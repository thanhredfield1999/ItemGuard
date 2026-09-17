using System;
using System.Collections;

namespace ItemGuard.Execution {
    // Host-only composition of two held NativeJournalLeaf capabilities.
    // This is not an authorization, recovery, or runtime authority entrypoint.
    public sealed class NativeDualJournalWriter : IDisposable {
        readonly NativeJournalLeaf first;
        readonly NativeJournalLeaf second;
        readonly DualJournalCommitCore core;
        bool disposed;

        NativeDualJournalWriter(NativeJournalLeaf first, NativeJournalLeaf second, int maximumRecords) {
            this.first = first;
            this.second = second;
            core = new DualJournalCommitCore(
                bytes => first.AppendAndFlush(bytes), () => { },
                bytes => second.AppendAndFlush(bytes), () => { },
                maximumRecords);
        }

        public static NativeDualJournalWriter Create(string firstParent, string secondParent, string leafName, int maximumRecords) {
            if (maximumRecords <= 0) throw new ArgumentOutOfRangeException("maximumRecords");
            NativeJournalLeaf first = null;
            NativeJournalLeaf second = null;
            try {
                first = NativeJournalLeaf.Create(firstParent, leafName);
                second = NativeJournalLeaf.Create(secondParent, leafName);
                return new NativeDualJournalWriter(first, second, maximumRecords);
            } catch {
                // Close capabilities but never delete a created leaf: a partial genesis remains observable.
                if (second != null) second.Dispose();
                if (first != null) first.Dispose();
                throw;
            }
        }

        public bool Failed { get { return core.Failed; } }
        public int CommittedCount { get { return core.CommittedCount; } }
        public string LastCommittedLineSha256 { get { return core.LastCommittedLineSha256; } }
        public NativeFileIdentity FirstIdentity { get { return first.Identity; } }
        public NativeFileIdentity SecondIdentity { get { return second.Identity; } }

        public bool TryCommit(IDictionary record) {
            if (disposed) return false;
            return core.TryCommit(record);
        }

        public void Dispose() {
            if (disposed) return;
            disposed = true;
            second.Dispose();
            first.Dispose();
        }
    }
}
