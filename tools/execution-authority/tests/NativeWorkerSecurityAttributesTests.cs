using System;
using ItemGuard.Execution;

class NativeWorkerSecurityAttributesTests {
    static int checks;
    static void Check(bool condition, string label) {
        checks++;
        if (!condition) throw new Exception(label);
    }

    static int Main() {
        try {
            const string sid = "S-1-5-21-100-200-300-400";
            using (var attributes = NativeWorkerSecurityAttributes.Create(sid)) {
                Check(attributes.ProcessAttributes != IntPtr.Zero, "process SECURITY_ATTRIBUTES exists before CreateProcessAsUserW");
                Check(attributes.ThreadAttributes != IntPtr.Zero, "thread SECURITY_ATTRIBUTES exists before CreateProcessAsUserW");
                Check(attributes.ProcessDescriptor != IntPtr.Zero, "process DACL descriptor survives through the native call");
                Check(attributes.ThreadDescriptor != IntPtr.Zero, "thread DACL descriptor survives through the native call");
                Check(!attributes.ProcessInheritHandle && !attributes.ThreadInheritHandle,
                    "worker process and thread security handles are never inheritable");
                string processSddl = attributes.ReadProcessDescriptorSddl();
                string threadSddl = attributes.ReadThreadDescriptorSddl();
                // Windows canonicalizes SDDL numeric masks by removing leading zeros.
                Check(processSddl.Contains("D:P(D;;0xc0aeb;;;" + sid + ")(A;;GR;;;" + sid + ")"),
                    "native process descriptor round-trips the protected deny-before-read DACL: " + processSddl);
                Check(threadSddl.Contains("D:P(D;;CCDCRPWPWDWO;;;" + sid + ")(A;;GR;;;" + sid + ")"),
                    "native thread descriptor round-trips the protected deny-before-read DACL: " + threadSddl);
            }
            Console.WriteLine("NATIVE_WORKER_SECURITY_ATTRIBUTES checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
    }
}
