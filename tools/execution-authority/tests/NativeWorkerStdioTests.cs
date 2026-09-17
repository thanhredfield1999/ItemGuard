using System;
using ItemGuard.Execution;

class NativeWorkerStdioTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }

    static int Main() {
        try {
            using (var stdio = NativeWorkerStdio.Create()) {
                Check(stdio.ChildStdin != IntPtr.Zero && stdio.ChildStdout != IntPtr.Zero && stdio.ChildStderr != IntPtr.Zero,
                    "each child stdio endpoint is a real pipe handle");
                Check(stdio.GuardianStdin != IntPtr.Zero && stdio.GuardianStdout != IntPtr.Zero && stdio.GuardianStderr != IntPtr.Zero,
                    "guardian retains every corresponding pipe endpoint");
                Check(stdio.ChildEndpointsInheritable(), "only the three child pipe endpoints are inheritable");
                Check(stdio.GuardianEndpointsNonInheritable(), "guardian pipe endpoints are not inheritable");
                Check(stdio.HasDistinctEndpoints(), "stdin stdout and stderr never alias one another");
                stdio.SealAfterCreate();
                Check(stdio.ChildStdin == IntPtr.Zero && stdio.ChildStdout == IntPtr.Zero && stdio.ChildStderr == IntPtr.Zero,
                    "guardian closes every child endpoint immediately after CreateProcessAsUserW");
                Check(stdio.GuardianStdin != IntPtr.Zero && stdio.GuardianStdout != IntPtr.Zero && stdio.GuardianStderr != IntPtr.Zero,
                    "guardian keeps its three endpoints after child-endpoint closure");
            }
            Console.WriteLine("NATIVE_WORKER_STDIO checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
    }
}
