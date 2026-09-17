using System;
using ItemGuard.Execution;

class NativeTokenShapeTests {
    static int checks;
    static void Check(bool condition, string label) { checks++; if (!condition) throw new Exception(label); }
    static int Main() {
        try {
            NativeTokenShape shape = NativeTokenShape.ReadCurrentProcess();
            Check(!shape.IsRestricted, "guardian primary token has no restricting SID");
            Check(shape.IntegrityRid >= NativeTokenShape.MediumIntegrityRid, "guardian primary token is not below Medium integrity");
            Check(shape.UserSid != null && shape.UserSid.Length > 0, "guardian primary token exposes current user SID");
            Console.WriteLine("NATIVE_TOKEN_SHAPE checks=" + checks + " failures=0");
            return 0;
        } catch (Exception ex) { Console.WriteLine("FAIL " + ex); return 1; }
    }
}
