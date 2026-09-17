using System;

namespace ItemGuard.Execution {
    // Receipt-to-journal boundary for an authorized controlled invocation. It deliberately
    // stops after AUTHORIZATION_CONSUMED: no runtime tree, worker, Paper or product action.
    public sealed class ControlledInvocationGate : IDisposable {
        readonly NativeOneShotNamespaceAdmission admission;
        public ControlledAuthorizationReceipt Receipt { get; private set; }
        public string RunToken { get; private set; }
        public int CommittedCount { get { return admission.CommittedCount; } }

        ControlledInvocationGate(NativeOneShotNamespaceAdmission admission,
            ControlledAuthorizationReceipt receipt, string runToken) {
            this.admission = admission;
            Receipt = receipt;
            RunToken = runToken;
        }

        public static ControlledInvocationGate Consume(string receiptParent, string receiptLeaf,
            NativeFileIdentity receiptIdentity, int receiptMaximumBytes,
            string firstJournalParent, string secondJournalParent, string runtimeParent,
            string journalLeafName, string runtimeLeafName, int maximumJournalRecords,
            string expectedNamespace, string bundleManifestSha256,
            string executionAuthorityManifestSha256, string recoveryManifestContentSha256,
            string reviewReceiptSha256) {
            // All receipt checks precede namespace mutation; an invalid or replaced receipt
            // must leave a still-fresh namespace untouched.
            GuardianAuthorizationReceipt validated = GuardianFixedReceiptReader.ReadBound(receiptParent,
                receiptLeaf, receiptIdentity, receiptMaximumBytes, expectedNamespace,
                bundleManifestSha256, executionAuthorityManifestSha256,
                recoveryManifestContentSha256, reviewReceiptSha256);
            NativeOneShotNamespaceAdmission admission = NativeOneShotNamespaceAdmission.Create(
                firstJournalParent, secondJournalParent, runtimeParent, journalLeafName,
                runtimeLeafName, maximumJournalRecords);
            try {
                if (!admission.TryCommitAuthorityReady(validated.Binding))
                    throw new InvalidOperationException("dual AUTHORITY_READY commit failed");
                if (!admission.TryCommitAuthorizationConsumed())
                    throw new InvalidOperationException("dual AUTHORIZATION_CONSUMED commit failed");
                return new ControlledInvocationGate(admission, validated.Receipt, admission.RunToken);
            } catch {
                admission.Dispose();
                throw;
            }
        }

        public void Dispose() { admission.Dispose(); }
    }
}
