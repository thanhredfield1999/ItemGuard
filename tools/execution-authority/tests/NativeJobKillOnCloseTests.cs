using System;
using System.Diagnostics;
using System.Threading;
using ItemGuard.Execution;

class NativeJobKillOnCloseTests {
    static int checks;
    static void Check(bool condition, string label) { checks++; if (!condition) throw new Exception(label); }
    static int Main() {
        Process child = null;
        try {
            child = Process.Start(new ProcessStartInfo(Environment.GetEnvironmentVariable("ComSpec"), "/d /s /c ping -t 127.0.0.1") {
                UseShellExecute = false, CreateNoWindow = true
            });
            Check(child != null && !child.HasExited, "disposable child starts");
            using (var job = NativeKillOnCloseJob.Create()) {
                job.Assign(child);
                Check(job.ActiveProcessLimit == 1, "job constrains active process count to one");
            }
            child.WaitForExit(5000);
            Check(child.HasExited, "closing owned job terminates assigned child");
            Console.WriteLine("NATIVE_JOB_KILL_ON_CLOSE checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex); return 1;
        } finally {
            try { if (child != null && !child.HasExited) child.Kill(); } catch { }
            if (child != null) child.Dispose();
        }
    }
}
