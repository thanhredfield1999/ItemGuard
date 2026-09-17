using System;
using System.Collections;
using System.IO;
using ItemGuard.Execution;

class NativeAuthorityReadyTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }
    static int Main() {
        string id = Guid.NewGuid().ToString("N");
        string firstRoot = "E:/AI.WORK/scratch-execution-authority-native-ready-e-" + id;
        string secondRoot = "C:/Users/thanh/AppData/Local/Temp/scratch-execution-authority-native-ready-c-" + id;
        try {
            Directory.CreateDirectory(firstRoot);
            Directory.CreateDirectory(secondRoot);
            var admission = NativeDualJournalAdmission.Create(firstRoot, secondRoot, "authority.jsonl", 2);
            try {
                Check(admission.Receipt.RunToken.Length == 36
                    && admission.Receipt.RunToken[14] == '4'
                    && "89ab".IndexOf(admission.Receipt.RunToken[19]) >= 0,
                    "admission mints one canonical UUID-v4 run token after dual leaf creation");
                Check(!admission.TryCommitAuthorizationConsumed(),
                    "authorization consumption is rejected before durable authority-ready genesis");
                Check(admission.CommittedCount == 0,
                    "rejected pre-genesis consumption writes no journal record");
                Check(admission.TryCommitAuthorityReady(), "admission creates a dual-flushed AUTHORITY_READY genesis record");
                Check(admission.CommittedCount == 1, "authority-ready is exactly the first committed record");
                Check(admission.TryCommitAuthorizationConsumed(), "admission commits authorization consumption only after authority-ready");
                Check(admission.CommittedCount == 2, "authorization consumption is exactly the second committed record");
            } finally {
                admission.Dispose();
            }
            {
                string first = File.ReadAllText(Path.Combine(firstRoot, "authority.jsonl"));
                string second = File.ReadAllText(Path.Combine(secondRoot, "authority.jsonl"));
                Check(first == second, "authority-ready bytes are identical across both mirrors");
                Check(first.Contains("\"event\":\"AUTHORITY_READY\""), "genesis identifies AUTHORITY_READY exactly");
                Check(first.Contains("\"runToken\":\""), "genesis binds the minted run token");
                Check(first.Contains("\"event\":\"AUTHORIZATION_CONSUMED\""), "second record identifies authorization consumption exactly");
                Check(first.Contains("\"firstVolumeInformationSerial\":"), "genesis binds first GetVolumeInformation serial");
                Check(first.Contains("\"secondVolumeInformationSerial\":"), "genesis binds second GetVolumeInformation serial");
                Check(first.Contains("\"firstFileIdHex\":\""), "genesis binds first FILE_ID_INFO identity");
                Check(first.Contains("\"secondFileIdHex\":\""), "genesis binds second FILE_ID_INFO identity");
            }
            Console.WriteLine("NATIVE_AUTHORITY_READY checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        } finally {
            try { if (Directory.Exists(firstRoot)) Directory.Delete(firstRoot, true); } catch (Exception ex) { Console.Error.WriteLine("E_CLEANUP_FAIL " + ex.Message); }
            try { if (Directory.Exists(secondRoot)) Directory.Delete(secondRoot, true); } catch (Exception ex) { Console.Error.WriteLine("C_CLEANUP_FAIL " + ex.Message); }
        }
    }
}
