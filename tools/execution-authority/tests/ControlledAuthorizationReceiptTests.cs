using System;
using System.Collections;
using ItemGuard.Execution;

class ControlledAuthorizationReceiptTests {
    static int checks;
    const string Bundle = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const string Authority = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    const string Recovery = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    const string Review = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

    static void Check(bool value, string label) {
        checks++;
        if (!value) throw new Exception(label);
    }
    static Hashtable Valid() {
        var receipt = new Hashtable();
        receipt["receiptType"] = "CONTROLLED_PAPER_ONE_SHOT";
        receipt["authorizedControlledNamespace"] = "attempt-15";
        receipt["bundleManifestSha256"] = Bundle;
        receipt["executionAuthorityManifestSha256"] = Authority;
        receipt["recoveryManifestContentSha256"] = Recovery;
        receipt["reviewReceiptSha256"] = Review;
        receipt["userAuthorizationUnixMs"] = (ulong)1;
        receipt["production"] = false;
        receipt["release"] = false;
        receipt["retry"] = false;
        return receipt;
    }
    static bool Rejected(Hashtable receipt) {
        try {
            ControlledAuthorizationReceipt.Validate(receipt, "attempt-15", Bundle, Authority, Recovery, Review);
            return false;
        } catch (FormatException) {
            return true;
        }
    }
    static bool BytesRejected(Hashtable receipt, byte[] bytes) {
        try {
            ControlledAuthorizationReceipt.ValidateCanonicalDocument(receipt, bytes, "attempt-15", Bundle, Authority, Recovery, Review);
            return false;
        } catch (FormatException) {
            return true;
        }
    }
    static int Main() {
        try {
            var valid = Valid();
            ControlledAuthorizationReceipt parsed = ControlledAuthorizationReceipt.Validate(valid, "attempt-15", Bundle, Authority, Recovery, Review);
            Check(parsed.Namespace == "attempt-15" && parsed.UserAuthorizationUnixMs == 1,
                "exact receipt binds the declared namespace and user authorization timestamp");
            byte[] canonical = CanonicalJson.EncodeDocument(valid);
            parsed = ControlledAuthorizationReceipt.ValidateCanonicalDocument(valid, canonical, "attempt-15", Bundle, Authority, Recovery, Review);
            Check(parsed.Namespace == "attempt-15", "canonical receipt bytes bind the validated receipt");
            IDictionary decoded = GuardianCanonicalDecoder.ParseDocument(canonical);
            parsed = ControlledAuthorizationReceipt.ValidateCanonicalDocument(decoded, canonical, "attempt-15", Bundle, Authority, Recovery, Review);
            Check(parsed.UserAuthorizationUnixMs == 1,
                "guardian-local decoder preserves canonical authorization fields for validation");
            var trailing = new byte[canonical.Length + 1];
            Buffer.BlockCopy(canonical, 0, trailing, 0, canonical.Length);
            trailing[trailing.Length - 1] = (byte)' ';
            Check(BytesRejected(valid, trailing), "non-canonical trailing byte is rejected before receipt acceptance");
            valid["unknown"] = "injected";
            Check(Rejected(valid), "unknown receipt member is rejected");
            valid = Valid(); valid["retry"] = true;
            Check(Rejected(valid), "retry scope cannot authorize a one-shot invocation");
            valid = Valid(); valid["production"] = true;
            Check(Rejected(valid), "production scope cannot authorize a controlled invocation");
            valid = Valid(); valid["release"] = true;
            Check(Rejected(valid), "release scope cannot authorize a controlled invocation");
            valid = Valid(); valid["authorizedControlledNamespace"] = "attempt-16";
            Check(Rejected(valid), "wrong namespace cannot authorize this invocation");
            valid = Valid(); valid["reviewReceiptSha256"] = new string('e', 64);
            Check(Rejected(valid), "stale review receipt hash is rejected");
            valid = Valid(); valid["userAuthorizationUnixMs"] = "1";
            Check(Rejected(valid), "authorization timestamp must be an unsigned integer, not text");
            Console.WriteLine("CONTROLLED_AUTHORIZATION_RECEIPT checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
    }
}
