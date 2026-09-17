using System;
using ItemGuard.Execution;

class NativeRestrictedTokenTests {
    static int checks;
    static void Check(bool condition, string label) { checks++; if (!condition) throw new Exception(label); }

    static int Main() {
        try {
            using (NativeRestrictedToken token = NativeRestrictedToken.CreateLowIntegrityFromCurrentProcess()) {
                NativeTokenShape shape = token.ReadShape();
                Check(!shape.IsRestricted, "worker token uses privilege restriction without restricting SIDs");
                Check(shape.IntegrityRid == NativeTokenShape.LowIntegrityRid, "worker token has exact Low integrity RID");
                Check(shape.UserSid != null && shape.UserSid.Length > 0, "worker token retains an attributable user SID");
                Check(token.IsPrimary, "worker token is a primary token usable only for guardian-controlled launch");
            }
            Console.WriteLine("NATIVE_RESTRICTED_TOKEN checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
    }
}
