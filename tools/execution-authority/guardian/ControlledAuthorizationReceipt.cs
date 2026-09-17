using System;
using System.Collections;

namespace ItemGuard.Execution {
    // In-memory schema validator for the externally stored controlled-run receipt.
    // It validates neither receipt path/identity nor journal creation.
    public sealed class ControlledAuthorizationReceipt {
        const string ReceiptType = "CONTROLLED_PAPER_ONE_SHOT";
        const int RequiredFieldCount = 10;

        public string Namespace { get; private set; }
        public ulong UserAuthorizationUnixMs { get; private set; }

        ControlledAuthorizationReceipt(string controlledNamespace, ulong userAuthorizationUnixMs) {
            Namespace = controlledNamespace;
            UserAuthorizationUnixMs = userAuthorizationUnixMs;
        }

        public static ControlledAuthorizationReceipt Validate(IDictionary receipt,
            string expectedNamespace, string bundleManifestSha256,
            string executionAuthorityManifestSha256, string recoveryManifestContentSha256,
            string reviewReceiptSha256) {
            if (receipt == null || receipt.Count != RequiredFieldCount)
                throw new FormatException("exact controlled authorization receipt fields required");
            RequireString(receipt, "receiptType", ReceiptType);
            RequireString(receipt, "authorizedControlledNamespace", expectedNamespace);
            RequireHash(receipt, "bundleManifestSha256", bundleManifestSha256);
            RequireHash(receipt, "executionAuthorityManifestSha256", executionAuthorityManifestSha256);
            RequireHash(receipt, "recoveryManifestContentSha256", recoveryManifestContentSha256);
            RequireHash(receipt, "reviewReceiptSha256", reviewReceiptSha256);
            object timestamp = Require(receipt, "userAuthorizationUnixMs");
            if (!(timestamp is ulong)) throw new FormatException("user authorization timestamp must be unsigned integer");
            RequireFalse(receipt, "production");
            RequireFalse(receipt, "release");
            RequireFalse(receipt, "retry");
            return new ControlledAuthorizationReceipt(expectedNamespace, (ulong)timestamp);
        }

        // The fixed-path reader supplies parsed data and its original bytes. Schema
        // validation alone is insufficient: those bytes must be the one canonical
        // document representation of the accepted map.
        public static ControlledAuthorizationReceipt ValidateCanonicalDocument(IDictionary receipt,
            byte[] documentBytes, string expectedNamespace, string bundleManifestSha256,
            string executionAuthorityManifestSha256, string recoveryManifestContentSha256,
            string reviewReceiptSha256) {
            if (documentBytes == null) throw new FormatException("controlled authorization receipt bytes required");
            byte[] canonical;
            try {
                canonical = CanonicalJson.EncodeDocument(receipt);
            } catch (ArgumentException exception) {
                throw new FormatException("controlled authorization receipt cannot be canonically encoded", exception);
            }
            if (canonical.Length != documentBytes.Length)
                throw new FormatException("controlled authorization receipt is not canonical bytes");
            for (int index = 0; index < canonical.Length; index++) {
                if (canonical[index] != documentBytes[index])
                    throw new FormatException("controlled authorization receipt is not canonical bytes");
            }
            return Validate(receipt, expectedNamespace, bundleManifestSha256,
                executionAuthorityManifestSha256, recoveryManifestContentSha256, reviewReceiptSha256);
        }

        static object Require(IDictionary receipt, string name) {
            if (!receipt.Contains(name)) throw new FormatException("missing receipt field " + name);
            return receipt[name];
        }
        static void RequireString(IDictionary receipt, string name, string expected) {
            object value = Require(receipt, name);
            if (!(value is string) || (string)value != expected)
                throw new FormatException("receipt field mismatch " + name);
        }
        static void RequireHash(IDictionary receipt, string name, string expected) {
            if (!IsLowerHex64(expected)) throw new ArgumentException("expected hash must be lowercase hex-64", name);
            RequireString(receipt, name, expected);
        }
        static void RequireFalse(IDictionary receipt, string name) {
            object value = Require(receipt, name);
            if (!(value is bool) || (bool)value) throw new FormatException("receipt scope must be false: " + name);
        }
        static bool IsLowerHex64(string value) {
            if (value == null || value.Length != 64) return false;
            foreach (char c in value) {
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
            }
            return true;
        }
    }
}
