using System;
using System.Collections;

namespace ItemGuard.Execution {
    // Host-only admission binding. It creates no process and performs no runtime authorization.
    public sealed class NativeDualJournalAdmission : IDisposable {
        readonly NativeDualJournalWriter writer;
        public NativeJournalAdmissionReceipt Receipt { get; private set; }

        NativeDualJournalAdmission(NativeDualJournalWriter writer, NativeJournalAdmissionReceipt receipt) {
            this.writer = writer;
            Receipt = receipt;
        }

        public static NativeDualJournalAdmission Create(string firstParent, string secondParent, string leafName, int maximumRecords) {
            NativeVolumeIdentity firstVolume = NativeVolumeIdentity.Read(firstParent);
            NativeVolumeIdentity secondVolume = NativeVolumeIdentity.Read(secondParent);
            if (!firstVolume.HasPersistentAcls) throw new InvalidOperationException("first journal root lacks persistent ACLs");
            if (!secondVolume.HasPersistentAcls) throw new InvalidOperationException("second journal root lacks persistent ACLs");
            NativeDualJournalWriter writer = NativeDualJournalWriter.Create(firstParent, secondParent, leafName, maximumRecords);
            try {
                string runToken = Guid.NewGuid().ToString("D");
                return new NativeDualJournalAdmission(writer, new NativeJournalAdmissionReceipt(
                    firstVolume, secondVolume, writer.FirstIdentity, writer.SecondIdentity, runToken));
            } catch {
                writer.Dispose();
                throw;
            }
        }

        public int CommittedCount { get { return writer.CommittedCount; } }

        public bool TryCommitAuthorityReady() { return TryCommitAuthorityReady(null); }

        public bool TryCommitAuthorityReady(AuthorizationReceiptBinding authorizationReceipt) {
            if (writer.CommittedCount != 0 || writer.Failed) return false;
            var record = new Hashtable();
            record["seq"] = (ulong)0;
            record["previousRecordSha256"] = new string('0', 64);
            record["event"] = "AUTHORITY_READY";
            record["runToken"] = Receipt.RunToken;
            record["firstVolumeInformationSerial"] = (ulong)Receipt.FirstVolumeInformationSerial;
            record["secondVolumeInformationSerial"] = (ulong)Receipt.SecondVolumeInformationSerial;
            record["firstFileIdInfoVolumeSerial"] = Receipt.FirstFileIdInfoVolumeSerial;
            record["secondFileIdInfoVolumeSerial"] = Receipt.SecondFileIdInfoVolumeSerial;
            record["firstFileIdHex"] = Receipt.FirstLeaf.FileIdHex;
            record["secondFileIdHex"] = Receipt.SecondLeaf.FileIdHex;
            if (authorizationReceipt != null) {
                record["authorizationReceiptVolumeSerial"] = authorizationReceipt.Identity.VolumeSerial;
                record["authorizationReceiptFileIdHex"] = authorizationReceipt.Identity.FileIdHex;
                record["bundleManifestSha256"] = authorizationReceipt.BundleManifestSha256;
                record["executionAuthorityManifestSha256"] = authorizationReceipt.ExecutionAuthorityManifestSha256;
                record["recoveryManifestContentSha256"] = authorizationReceipt.RecoveryManifestContentSha256;
                record["reviewReceiptSha256"] = authorizationReceipt.ReviewReceiptSha256;
            }
            record["recordSha256"] = CanonicalJson.ComputeRecordSha256(record);
            return writer.TryCommit(record);
        }

        public bool TryCommitAuthorizationConsumed() {
            if (writer.CommittedCount != 1 || writer.Failed) return false;
            var record = new Hashtable();
            record["seq"] = (ulong)1;
            record["previousRecordSha256"] = writer.LastCommittedLineSha256;
            record["event"] = "AUTHORIZATION_CONSUMED";
            record["runToken"] = Receipt.RunToken;
            record["recordSha256"] = CanonicalJson.ComputeRecordSha256(record);
            return writer.TryCommit(record);
        }

        public void Dispose() { writer.Dispose(); }
    }

    public sealed class NativeJournalAdmissionReceipt {
        public NativeVolumeIdentity FirstVolume { get; private set; }
        public NativeVolumeIdentity SecondVolume { get; private set; }
        public NativeFileIdentity FirstLeaf { get; private set; }
        public NativeFileIdentity SecondLeaf { get; private set; }
        public string RunToken { get; private set; }
        public uint FirstVolumeInformationSerial { get { return FirstVolume.VolumeInformationSerial; } }
        public uint SecondVolumeInformationSerial { get { return SecondVolume.VolumeInformationSerial; } }
        public ulong FirstFileIdInfoVolumeSerial { get { return FirstLeaf.VolumeSerial; } }
        public ulong SecondFileIdInfoVolumeSerial { get { return SecondLeaf.VolumeSerial; } }

        internal NativeJournalAdmissionReceipt(NativeVolumeIdentity firstVolume, NativeVolumeIdentity secondVolume,
            NativeFileIdentity firstLeaf, NativeFileIdentity secondLeaf, string runToken) {
            if (firstVolume == null || secondVolume == null || firstLeaf == null || secondLeaf == null)
                throw new ArgumentNullException("receipt binding");
            Guid parsed;
            if (!Guid.TryParseExact(runToken, "D", out parsed) || runToken != parsed.ToString("D")
                || runToken[14] != '4' || "89ab".IndexOf(runToken[19]) < 0)
                throw new ArgumentException("canonical UUID-v4 run token required");
            FirstVolume = firstVolume;
            SecondVolume = secondVolume;
            FirstLeaf = firstLeaf;
            SecondLeaf = secondLeaf;
            RunToken = runToken;
        }
    }

}
