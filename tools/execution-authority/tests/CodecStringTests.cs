using System;
using System.Text;
using System.Reflection;
class CodecStringTests {
    static int Main(string[] args) {
        try {
            var type = Assembly.LoadFrom(args[0]).GetType("ItemGuard.Execution.CanonicalString", true);
            var method = type.GetMethod("Encode");
            string[] inputs = { "", "a+b/<>&'", "\"\\\n\t\0", "rôle", "\U0001F600", "\uFEFF" };
            string[] expected = { "\"\"", "\"a+b/<>&'\"", "\"\\\"\\\\\\u000a\\u0009\\u0000\"", "\"rôle\"", "\"\U0001F600\"", "\"\uFEFF\"" };
            for (int i=0;i<inputs.Length;i++) {
                var actual=(byte[])method.Invoke(null,new object[]{inputs[i]});
                if (Convert.ToBase64String(actual)!=Convert.ToBase64String(new UTF8Encoding(false,true).GetBytes(expected[i]))) throw new Exception("vector "+i);
            }
            foreach (var invalid in new string[]{"\uD800", "\uDC00", "a\uD800b"}) {
                bool rejected=false;
                try { method.Invoke(null,new object[]{invalid}); }
                catch(TargetInvocationException e) { rejected=e.InnerException is ArgumentException; }
                if(!rejected) throw new Exception("unpaired surrogate accepted");
            }
            Console.WriteLine("PASS 6 encoding vectors + 3 invalid-surrogate cases"); return 0;
        } catch(Exception e) { Console.Error.WriteLine("FAIL "+e); return 1; }
    }
}
