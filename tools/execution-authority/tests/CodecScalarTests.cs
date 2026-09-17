using System;
using System.Collections;
using System.Collections.Generic;
using System.Reflection;
using System.Text;

// Slice 2 (behavioral): document root rule + scalar member encoding.
// Guardian encoder surface under test: ItemGuard.Execution.CanonicalJson.EncodeDocument(IDictionary) -> byte[]
class CodecScalarTests {
    static Type type;
    static MethodInfo encode;
    static int failures;

    static byte[] Enc(IDictionary doc) {
        try { return (byte[])encode.Invoke(null, new object[] { doc }); }
        catch (TargetInvocationException e) { throw e.InnerException; }
    }

    static void Expect(string name, IDictionary doc, string expectedUtf8) {
        byte[] want = new UTF8Encoding(false, true).GetBytes(expectedUtf8);
        byte[] got;
        try { got = Enc(doc); }
        catch (Exception e) { failures++; Console.Error.WriteLine("FAIL " + name + ": threw " + e.GetType().Name + " " + e.Message); return; }
        if (Convert.ToBase64String(got) != Convert.ToBase64String(want)) {
            failures++;
            Console.Error.WriteLine("FAIL " + name + ": want " + BitConverter.ToString(want) + " got " + BitConverter.ToString(got));
        }
    }

    static void ExpectReject(string name, object doc) {
        try {
            encode.Invoke(null, new object[] { doc });
            failures++; Console.Error.WriteLine("FAIL " + name + ": accepted, expected ArgumentException");
        } catch (TargetInvocationException e) {
            if (!(e.InnerException is ArgumentException)) {
                failures++; Console.Error.WriteLine("FAIL " + name + ": wrong exception " + e.InnerException.GetType().Name);
            }
        }
    }

    static IDictionary One(string key, object value) {
        var d = new Dictionary<string, object>();
        d[key] = value;
        return d;
    }

    static int Main(string[] args) {
        try {
            type = Assembly.LoadFrom(args[0]).GetType("ItemGuard.Execution.CanonicalJson", true);
            encode = type.GetMethod("EncodeDocument", new Type[] { typeof(IDictionary) });
            if (encode == null) throw new MissingMethodException("CanonicalJson.EncodeDocument(IDictionary)");
        } catch (Exception e) {
            Console.Error.WriteLine("FAIL bootstrap " + e.GetType().Name + ": " + e.Message);
            return 1;
        }

        Expect("empty root object", new Dictionary<string, object>(), "{}");
        Expect("uint zero", One("v", (ulong)0), "{\"v\":0}");
        Expect("uint one", One("v", (ulong)1), "{\"v\":1}");
        Expect("uint max", One("v", ulong.MaxValue), "{\"v\":18446744073709551615}");
        Expect("bool true", One("v", true), "{\"v\":true}");
        Expect("bool false", One("v", false), "{\"v\":false}");
        Expect("string escapes", One("v", "\"\\\n"), "{\"v\":\"\\\"\\\\\\u000a\"}");
        Expect("string astral raw", One("v", "\U0001F600"), "{\"v\":\"\U0001F600\"}");
        Expect("key escaped like string", One("a\tb", (ulong)7), "{\"a\\u0009b\":7}");

        ExpectReject("null value", One("v", null));
        ExpectReject("int32 value", One("v", 1));
        ExpectReject("int64 value", One("v", (long)1));
        ExpectReject("double value", One("v", 1.5d));
        ExpectReject("decimal value", One("v", 1.0m));
        ExpectReject("unpaired surrogate value", One("v", "\uD800"));
        ExpectReject("unpaired surrogate key", One("\uDC00", (ulong)1));
        ExpectReject("root null", null);

        // Root-must-be-object is enforced statically on the encoder surface: the only
        // entry point takes IDictionary. Assert no non-object overload exists so a
        // future mutant cannot widen the root domain silently.
        foreach (MethodInfo m in type.GetMethods(BindingFlags.Public | BindingFlags.Static)) {
            if (m.Name != "EncodeDocument") continue;
            ParameterInfo[] ps = m.GetParameters();
            if (ps.Length < 1 || !typeof(IDictionary).IsAssignableFrom(ps[0].ParameterType)) {
                failures++;
                Console.Error.WriteLine("FAIL root domain: EncodeDocument overload accepts non-object root");
            }
        }

        if (failures > 0) { Console.Error.WriteLine("FAILURES=" + failures); return 1; }
        Console.WriteLine("PASS CodecScalarTests: 9 vectors + 8 rejection cases + root-domain guard");
        return 0;
    }
}
