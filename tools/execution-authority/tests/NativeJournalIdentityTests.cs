using System;
using System.IO;
using System.Security.AccessControl;
using ItemGuard.Execution;

class NativeJournalIdentityTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }
    static int Main() {
        string root = "E:/AI.WORK/scratch-execution-authority-native-identity-" + Guid.NewGuid().ToString("N");
        try {
            Directory.CreateDirectory(root);
            NativeFileIdentity first;
            using (var journal = NativeJournalLeaf.Create(root, "authority.jsonl")) {
                first = journal.Identity;
                Check(first.VolumeSerial != 0, "native file identity includes volume serial");
                Check(first.FileId != null && first.FileId.Length == 16, "native file identity includes all 16 FILE_ID_INFO bytes");
                Check(first.FileIdHex.Length == 32, "file identity exposes canonical fixed-width hex");
                Check(journal.AppendAndFlush(new byte[] { 10 }) == 1, "identity read does not invalidate append handle");
                Check(journal.Identity.FileIdHex == first.FileIdHex, "identity stays stable while held");
            }
            using (var reopened = NativeJournalLeaf.OpenExistingReadOnly(root, "authority.jsonl")) {
                Check(reopened.Identity.VolumeSerial == first.VolumeSerial && reopened.Identity.FileIdHex == first.FileIdHex,
                    "reopened exact leaf has the same native identity");
                Check(reopened.ReadAllExact(1024)[0] == 10,
                    "held read-only native leaf returns the exact durable bytes");
            }
            bool appendDenied = false;
            try {
                using (File.Open(Path.Combine(root, "authority.jsonl"), FileMode.Open, FileAccess.Write, FileShare.Read)) { }
            } catch (UnauthorizedAccessException) {
                appendDenied = true;
            }
            Check(appendDenied, "same-user append reopen stays denied after guardian handle close");
            FileSecurity security = File.GetAccessControl(Path.Combine(root, "authority.jsonl"));
            Check(security.AreAccessRulesProtected, "final journal DACL rejects inherited write grants");
            Console.WriteLine("NATIVE_JOURNAL_IDENTITY checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        } finally {
            try { if (Directory.Exists(root)) Directory.Delete(root, true); }
            catch (Exception ex) { Console.Error.WriteLine("CLEANUP_FAIL " + ex.Message); }
        }
    }
}
