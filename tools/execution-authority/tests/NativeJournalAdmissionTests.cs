using System;
using System.IO;
using ItemGuard.Execution;

class NativeJournalAdmissionTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }
    static int Main() {
        string id = Guid.NewGuid().ToString("N");
        string firstRoot = "E:/AI.WORK/scratch-execution-authority-native-admission-e-" + id;
        string secondRoot = "C:/Users/thanh/AppData/Local/Temp/scratch-execution-authority-native-admission-c-" + id;
        try {
            Directory.CreateDirectory(firstRoot);
            Directory.CreateDirectory(secondRoot);
            using (var admission = NativeDualJournalAdmission.Create(firstRoot, secondRoot, "authority.jsonl", 2)) {
                NativeJournalAdmissionReceipt receipt = admission.Receipt;
                Check(receipt.FirstLeaf.FileIdHex.Length == 32 && receipt.SecondLeaf.FileIdHex.Length == 32,
                    "receipt binds both native leaf IDs");
                Check(receipt.FirstVolume.HasPersistentAcls && receipt.SecondVolume.HasPersistentAcls,
                    "admission receipt records persistent-ACL requirement for both roots");
                Check(receipt.FirstLeaf.VolumeSerial == receipt.FirstFileIdInfoVolumeSerial
                    && receipt.SecondLeaf.VolumeSerial == receipt.SecondFileIdInfoVolumeSerial,
                    "receipt preserves FILE_ID_INFO volume serials without normalization");
                Check(receipt.FirstVolumeInformationSerial == receipt.FirstVolume.VolumeInformationSerial
                    && receipt.SecondVolumeInformationSerial == receipt.SecondVolume.VolumeInformationSerial,
                    "receipt preserves separate GetVolumeInformation serials");
            }
            Console.WriteLine("NATIVE_JOURNAL_ADMISSION checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        } finally {
            try { if (Directory.Exists(firstRoot)) Directory.Delete(firstRoot, true); }
            catch (Exception ex) { Console.Error.WriteLine("E_CLEANUP_FAIL " + ex.Message); }
            try { if (Directory.Exists(secondRoot)) Directory.Delete(secondRoot, true); }
            catch (Exception ex) { Console.Error.WriteLine("C_CLEANUP_FAIL " + ex.Message); }
        }
    }
}
