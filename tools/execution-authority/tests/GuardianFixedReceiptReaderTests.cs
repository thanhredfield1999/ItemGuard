using System;
using System.Collections;
using System.IO;
using ItemGuard.Execution;

class GuardianFixedReceiptReaderTests {
    static int checks;
    const string Bundle = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const string Authority = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    const string Recovery = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    const string Review = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    static void Check(bool value, string label) { checks++; if (!value) throw new Exception(label); }
    static Hashtable Receipt() {
        var value = new Hashtable();
        value["receiptType"] = "CONTROLLED_PAPER_ONE_SHOT";
        value["authorizedControlledNamespace"] = "attempt-15";
        value["bundleManifestSha256"] = Bundle;
        value["executionAuthorityManifestSha256"] = Authority;
        value["recoveryManifestContentSha256"] = Recovery;
        value["reviewReceiptSha256"] = Review;
        value["userAuthorizationUnixMs"] = (ulong)1;
        value["production"] = false; value["release"] = false; value["retry"] = false;
        return value;
    }
    static bool Reject(Action action) {
        try { action(); return false; } catch (FormatException) { return true; } catch (IOException) { return true; }
    }
    static int Main() {
        string root = "E:/AI.WORK/scratch-execution-authority-fixed-receipt-" + Guid.NewGuid().ToString("N");
        string changed = Path.Combine(root, "changed.json");
        try {
            Directory.CreateDirectory(root);
            NativeFileIdentity identity;
            byte[] bytes = CanonicalJson.EncodeDocument(Receipt());
            using (var leaf = NativeJournalLeaf.Create(root, "receipt.json")) {
                leaf.AppendAndFlush(bytes); identity = leaf.Identity;
            }
            var receipt = GuardianFixedReceiptReader.Read(root, "receipt.json", identity, 4096,
                "attempt-15", Bundle, Authority, Recovery, Review);
            Check(receipt.Namespace == "attempt-15", "fixed native receipt leaf is decoded and bound");
            using (var leaf = NativeJournalLeaf.Create(root, "changed.json")) { leaf.AppendAndFlush(bytes); }
            Check(Reject(() => GuardianFixedReceiptReader.Read(root, "changed.json", identity, 4096,
                "attempt-15", Bundle, Authority, Recovery, Review)),
                "different fixed-path leaf identity is rejected before authorization");
            bool replacementDenied = false;
            try { File.WriteAllText(changed, "{}"); } catch (UnauthorizedAccessException) { replacementDenied = true; }
            Check(replacementDenied,
                "final leaf DACL prevents same-user path replacement after capture");
            Console.WriteLine("GUARDIAN_FIXED_RECEIPT_READER checks=" + checks + " failures=0");
            return 0;
        } catch (Exception exception) {
            Console.WriteLine("FAIL " + exception); return 1;
        } finally {
            try { if (Directory.Exists(root)) Directory.Delete(root, true); }
            catch (Exception exception) { Console.Error.WriteLine("CLEANUP_FAIL " + exception.Message); }
        }
    }
}
