using System;
using System.Collections;
using System.Collections.Specialized;
using System.Security.Cryptography;
using System.Text;

namespace ItemGuard.Recovery {
    // Pure recovery-side pre-image rule for the final shipped manifest.
    public static class RecoveryManifestPreImage {
        public const string EnforcementReceiptsMember = "enforcementReceipts";
        public static string ComputeContentSha256(IDictionary finalManifest) {
            if (finalManifest == null) throw new FormatException("final recovery manifest required");
            var preImage = new OrderedDictionary();
            foreach (DictionaryEntry entry in finalManifest) {
                string key = entry.Key as string;
                if (key == null) throw new FormatException("manifest member name must be a string");
                if (key == EnforcementReceiptsMember) continue;
                preImage.Add(key, entry.Value);
            }
            byte[] bytes = CanonicalReEncoder.Encode(preImage);
            using (var sha = SHA256.Create()) {
                byte[] hash = sha.ComputeHash(bytes);
                var text = new StringBuilder(64);
                foreach (byte value in hash) text.Append(value.ToString("x2", System.Globalization.CultureInfo.InvariantCulture));
                return text.ToString();
            }
        }
    }
}
