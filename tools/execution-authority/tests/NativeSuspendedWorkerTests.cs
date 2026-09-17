using System;
using System.IO;
using System.Threading;
using ItemGuard.Execution;

class NativeSuspendedWorkerTests {
    static int checks;
    static void Check(bool condition, string label) { checks++; if (!condition) throw new Exception(label); }

    static int Main() {
        string root = "E:/AI.WORK/scratch-execution-authority-native-worker-" + Guid.NewGuid().ToString("N");
        try {
            Directory.CreateDirectory(root);
            using (var token = NativeRestrictedToken.CreateLowIntegrityFromCurrentProcess())
            using (var job = NativeKillOnCloseJob.Create())
            using (var worker = NativeSuspendedWorker.Create(token, job)) {
                Thread.Sleep(250);
                Check(!worker.HasExited, "suspended worker executes no instruction before guardian release");
                Check(worker.IsInAssignedJob, "guardian verifies worker assignment before release");
                worker.Release();
                Check(worker.WaitForExit(5000) == 0, "released owned worker exits successfully");
                Check(worker.Released, "worker release state changes once after containment is verified");
            }
            Console.WriteLine("NATIVE_SUSPENDED_WORKER checks=" + checks + " failures=0");
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
