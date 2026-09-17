using System;
using System.IO;
using ItemGuard.Execution;

class NativeVolumeIdentityTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }
    static int Main() {
        string root = "E:/AI.WORK/scratch-execution-authority-native-volume-" + Guid.NewGuid().ToString("N");
        try {
            Directory.CreateDirectory(root);
            NativeVolumeIdentity volume = NativeVolumeIdentity.Read(root);
            Check(volume.VolumeInformationSerial != 0, "volume-information serial is read from the exact root");
            Check(volume.HasPersistentAcls, "NTFS journal root requires persistent ACL support");
            Check(!String.IsNullOrEmpty(volume.FileSystemName), "filesystem name is read back");
            Console.WriteLine("NATIVE_VOLUME_IDENTITY checks=" + checks + " failures=0");
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
