using System;
using System.Security.Principal;

namespace ItemGuard.Execution {
    // Pure, fail-closed SDDL construction for CREATE_SUSPENDED worker objects.
    // Converting/attaching the descriptor and read-back verification remain native
    // guardian responsibilities; this type never creates a process or handle.
    public static class NativeWorkerSecurityPolicy {
        const string ProcessSensitiveRights = "0x000c0aeb";
        const string ThreadSensitiveRights = "0x000c0033";

        public static string BuildProcessSddl(string workerSid) {
            return Build(workerSid, ProcessSensitiveRights);
        }

        public static string BuildThreadSddl(string workerSid) {
            return Build(workerSid, ThreadSensitiveRights);
        }

        static string Build(string workerSid, string deniedRights) {
            if (String.IsNullOrEmpty(workerSid)) throw new ArgumentException("workerSid");
            try {
                var sid = new SecurityIdentifier(workerSid);
                if (!String.Equals(workerSid, sid.Value, StringComparison.Ordinal))
                    throw new ArgumentException("canonical worker SID required");
            } catch (ArgumentException) {
                throw new ArgumentException("canonical worker SID required");
            }
            // Protected DACL: deny mutation/escape capabilities before the sole
            // generic-read allow ACE. No world or generic-all ACE is present.
            return "D:P(D;;" + deniedRights + ";;;" + workerSid + ")(A;;GR;;;" + workerSid + ")";
        }
    }
}
