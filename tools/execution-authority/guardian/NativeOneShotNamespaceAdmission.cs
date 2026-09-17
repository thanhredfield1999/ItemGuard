using System;
using System.IO;

namespace ItemGuard.Execution {
    // Native one-shot wrapper. It does not create the runtime directory: that is
    // deliberately deferred until both dual genesis records are durable.
    public sealed class NativeOneShotNamespaceAdmission : IDisposable {
        readonly NativeDualJournalAdmission admission;

        NativeOneShotNamespaceAdmission(NativeDualJournalAdmission admission) {
            this.admission = admission;
        }

        public static NativeOneShotNamespaceAdmission Create(string firstJournalParent,
            string secondJournalParent, string runtimeParent, string journalLeafName,
            string runtimeLeafName, int maximumRecords) {
            RequireLeafName(journalLeafName, "journalLeafName");
            if (String.IsNullOrEmpty(runtimeParent)) throw new ArgumentException("runtimeParent");
            RequireLeafName(runtimeLeafName, "runtimeLeafName");
            string firstLeaf = Path.Combine(Path.GetFullPath(firstJournalParent), journalLeafName);
            string secondLeaf = Path.Combine(Path.GetFullPath(secondJournalParent), journalLeafName);
            string runtimeLeaf = Path.Combine(Path.GetFullPath(runtimeParent), runtimeLeafName);
            if (NamespaceOneShotPolicy.Decide(File.Exists(firstLeaf), File.Exists(secondLeaf),
                File.Exists(runtimeLeaf) || Directory.Exists(runtimeLeaf)) != NamespaceAdmissionDecision.CreateFirstLeaf)
                throw new IOException("one-shot namespace already consumed or partial");
            // FILE_CREATE is the authority at the mutation boundary. A race after
            // preflight cannot overwrite an existing journal leaf and therefore
            // fails closed in NativeDualJournalWriter.Create.
            return new NativeOneShotNamespaceAdmission(NativeDualJournalAdmission.Create(
                firstJournalParent, secondJournalParent, journalLeafName, maximumRecords));
        }

        public bool TryCommitAuthorityReady() { return admission.TryCommitAuthorityReady(); }
        public bool TryCommitAuthorityReady(AuthorizationReceiptBinding receiptBinding) {
            return admission.TryCommitAuthorityReady(receiptBinding);
        }
        public bool TryCommitAuthorizationConsumed() { return admission.TryCommitAuthorizationConsumed(); }
        public int CommittedCount { get { return admission.CommittedCount; } }
        public string RunToken { get { return admission.Receipt.RunToken; } }
        public void Dispose() { admission.Dispose(); }

        static void RequireLeafName(string value, string parameterName) {
            if (String.IsNullOrEmpty(value) || value == "." || value == ".."
                || value.IndexOf(Path.DirectorySeparatorChar) >= 0
                || value.IndexOf(Path.AltDirectorySeparatorChar) >= 0
                || !String.Equals(Path.GetFileName(value), value, StringComparison.Ordinal))
                throw new ArgumentException(parameterName);
        }
    }
}
