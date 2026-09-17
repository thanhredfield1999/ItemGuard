using System;

namespace ItemGuard.Execution {
    // Immutable values validated from a fixed receipt leaf. Genesis retains these
    // bindings so recovery can identify exactly what authorization was consumed.
    public sealed class AuthorizationReceiptBinding {
        public NativeFileIdentity Identity { get; private set; }
        public string BundleManifestSha256 { get; private set; }
        public string ExecutionAuthorityManifestSha256 { get; private set; }
        public string RecoveryManifestContentSha256 { get; private set; }
        public string ReviewReceiptSha256 { get; private set; }
        public AuthorizationReceiptBinding(NativeFileIdentity identity, string bundleManifestSha256,
            string executionAuthorityManifestSha256, string recoveryManifestContentSha256,
            string reviewReceiptSha256) {
            if (identity == null || !CanonicalJson.IsLowerHex64(bundleManifestSha256)
                || !CanonicalJson.IsLowerHex64(executionAuthorityManifestSha256)
                || !CanonicalJson.IsLowerHex64(recoveryManifestContentSha256)
                || !CanonicalJson.IsLowerHex64(reviewReceiptSha256))
                throw new ArgumentException("exact receipt identity and lowercase hash bindings required");
            Identity = identity;
            BundleManifestSha256 = bundleManifestSha256;
            ExecutionAuthorityManifestSha256 = executionAuthorityManifestSha256;
            RecoveryManifestContentSha256 = recoveryManifestContentSha256;
            ReviewReceiptSha256 = reviewReceiptSha256;
        }
    }
}
