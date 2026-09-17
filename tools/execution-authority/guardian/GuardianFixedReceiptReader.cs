using System;
using System.Collections;
using System.IO;

namespace ItemGuard.Execution {
    // Reads an already-captured fixed receipt leaf through a non-reparse native
    // handle and requires its native identity before any authorization decision.
    public static class GuardianFixedReceiptReader {
        public static ControlledAuthorizationReceipt Read(string parentDirectory, string leafName,
            NativeFileIdentity expectedIdentity, int maximumBytes, string expectedNamespace,
            string bundleManifestSha256, string executionAuthorityManifestSha256,
            string recoveryManifestContentSha256, string reviewReceiptSha256) {
            return ReadBound(parentDirectory, leafName, expectedIdentity, maximumBytes,
                expectedNamespace, bundleManifestSha256, executionAuthorityManifestSha256,
                recoveryManifestContentSha256, reviewReceiptSha256).Receipt;
        }

        public static GuardianAuthorizationReceipt ReadBound(string parentDirectory, string leafName,
            NativeFileIdentity expectedIdentity, int maximumBytes, string expectedNamespace,
            string bundleManifestSha256, string executionAuthorityManifestSha256,
            string recoveryManifestContentSha256, string reviewReceiptSha256) {
            if (expectedIdentity == null) throw new ArgumentNullException("expectedIdentity");
            byte[] bytes; NativeFileIdentity actual;
            using (var leaf = NativeJournalLeaf.OpenExistingReadOnly(parentDirectory, leafName)) {
                actual = leaf.Identity;
                if (actual.VolumeSerial != expectedIdentity.VolumeSerial || actual.FileIdHex != expectedIdentity.FileIdHex)
                    throw new FormatException("fixed receipt native identity mismatch");
                bytes = leaf.ReadAllExact(maximumBytes);
            }
            IDictionary receipt = GuardianCanonicalDecoder.ParseDocument(bytes);
            var validated = ControlledAuthorizationReceipt.ValidateCanonicalDocument(receipt, bytes,
                expectedNamespace, bundleManifestSha256, executionAuthorityManifestSha256,
                recoveryManifestContentSha256, reviewReceiptSha256);
            return new GuardianAuthorizationReceipt(validated, new AuthorizationReceiptBinding(actual,
                bundleManifestSha256, executionAuthorityManifestSha256,
                recoveryManifestContentSha256, reviewReceiptSha256));
        }
    }

    public sealed class GuardianAuthorizationReceipt {
        public ControlledAuthorizationReceipt Receipt { get; private set; }
        public AuthorizationReceiptBinding Binding { get; private set; }
        public GuardianAuthorizationReceipt(ControlledAuthorizationReceipt receipt, AuthorizationReceiptBinding binding) {
            if (receipt == null || binding == null) throw new ArgumentNullException("validated receipt binding");
            Receipt = receipt; Binding = binding;
        }
    }
}
