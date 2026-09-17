using System;
using System.Collections;
using System.Collections.Generic;
using System.Reflection;
using System.Text;

// Slice 5 (behavioral): independent recovery-side strict decoder / canonical validator.
// Surface: ItemGuard.Recovery.CanonicalDecoder.ParseDocument(byte[]) -> IDictionary
// Contract: accepts ONLY exact canonical codec-v1 bytes. Anything a permissive JSON
// parser would tolerate must be rejected with FormatException.
class RecoveryDecoderTests {
    static MethodInfo parse;
    static int failures;
    static readonly UTF8Encoding U8 = new UTF8Encoding(false, true);

    static object Parse(byte[] input) {
        try { return parse.Invoke(null, new object[] { input }); }
        catch (TargetInvocationException e) { throw e.InnerException; }
    }

    static void Accept(string name, string json) {
        try {
            object result = Parse(U8.GetBytes(json));
            if (!(result is IDictionary)) {
                failures++; Console.Error.WriteLine("FAIL " + name + ": result is not IDictionary");
            }
        } catch (Exception e) {
            failures++; Console.Error.WriteLine("FAIL accept " + name + ": " + e.GetType().Name + " " + e.Message);
        }
    }

    static void Reject(string name, byte[] input) {
        try {
            Parse(input);
            failures++; Console.Error.WriteLine("FAIL reject " + name + ": accepted");
        } catch (FormatException) {
        } catch (Exception e) {
            failures++; Console.Error.WriteLine("FAIL reject " + name + ": wrong exception " + e.GetType().Name);
        }
    }

    static void Reject(string name, string json) { Reject(name, U8.GetBytes(json)); }

    static void ExpectValue(string name, string json, string key, object expected) {
        try {
            var d = (IDictionary)Parse(U8.GetBytes(json));
            object got = d[key];
            if (!Equals(got, expected)) {
                failures++;
                Console.Error.WriteLine("FAIL " + name + ": want " + expected + " (" + expected.GetType().Name +
                    ") got " + got + " (" + (got == null ? "null" : got.GetType().Name) + ")");
            }
        } catch (Exception e) {
            failures++; Console.Error.WriteLine("FAIL " + name + ": " + e.GetType().Name + " " + e.Message);
        }
    }

    static int Main(string[] args) {
        try {
            Type t = Assembly.LoadFrom(args[0]).GetType("ItemGuard.Recovery.CanonicalDecoder", true);
            parse = t.GetMethod("ParseDocument", new Type[] { typeof(byte[]) });
            if (parse == null) throw new MissingMethodException("CanonicalDecoder.ParseDocument(byte[])");
        } catch (Exception e) {
            Console.Error.WriteLine("FAIL bootstrap " + e.GetType().Name + ": " + e.Message);
            return 1;
        }

        // --- accepted canonical forms ---
        Accept("empty root", "{}");
        Accept("single member", "{\"a\":0}");
        Accept("sorted members", "{\"a\":1,\"b\":2}");
        Accept("empty containers", "{\"a\":[],\"o\":{}}");
        Accept("array order free", "{\"a\":[3,1,2]}");
        Accept("nested", "{\"a\":{\"b\":{\"c\":[true,false]}}}");
        Accept("uint max", "{\"v\":18446744073709551615}");
        Accept("escapes", "{\"v\":\"\\\"\\\\\\u0000\\u001f\"}");
        Accept("raw non-ascii", "{\"v\":\"r\u00f4le \U0001F600\"}");
        Accept("interior BOM is data", "{\"v\":\"a\uFEFFb\"}");
        Accept("empty key sorts first", "{\"\":0,\"a\":1}");
        Accept("utf8 key order astral last", "{\"\uFF3A\":1,\"\U0001F600\":2}");

        // --- decoded value domain ---
        ExpectValue("uint decodes to UInt64", "{\"v\":7}", "v", (ulong)7);
        ExpectValue("max uint decodes", "{\"v\":18446744073709551615}", "v", ulong.MaxValue);
        ExpectValue("true decodes to bool", "{\"v\":true}", "v", true);
        ExpectValue("false decodes to bool", "{\"v\":false}", "v", false);
        ExpectValue("string decodes", "{\"v\":\"x\"}", "v", "x");
        ExpectValue("control escape decodes", "{\"v\":\"\\u000a\"}", "v", "\n");

        // --- root domain ---
        Reject("root array", "[]");
        Reject("root string", "\"x\"");
        Reject("root integer", "1");
        Reject("root true", "true");
        Reject("empty input", new byte[0]);

        // --- whitespace is never canonical ---
        Reject("leading space", " {}");
        Reject("trailing space", "{} ");
        Reject("space after colon", "{\"a\": 1}");
        Reject("space after comma", "{\"a\":1, \"b\":2}");
        Reject("newline inside document", "{\"a\":\n1}");
        Reject("trailing LF", "{}\n");
        Reject("tab between tokens", "{\t}");

        // --- structural ---
        Reject("trailing bytes", "{}{}");
        Reject("truncated object", "{\"a\":1");
        Reject("truncated string", "{\"a\":\"x}");
        Reject("truncated array", "{\"a\":[1}");
        Reject("trailing comma object", "{\"a\":1,}");
        Reject("trailing comma array", "{\"a\":[1,]}");
        Reject("missing colon", "{\"a\"1}");
        Reject("unquoted key", "{a:1}");
        Reject("single quoted string", "{'a':1}");

        // --- key ordering and duplicates ---
        Reject("unsorted keys", "{\"b\":1,\"a\":2}");
        Reject("duplicate keys", "{\"a\":1,\"a\":2}");
        Reject("prefix ordering violated", "{\"aa\":1,\"a\":2}");
        // UTF-16 ordinal order would put the astral key first; UTF-8 byte order must not.
        Reject("utf16 ordinal order rejected", "{\"\U0001F600\":1,\"\uFF3A\":2}");

        // --- integer domain ---
        Reject("negative", "{\"v\":-1}");
        Reject("leading zero", "{\"v\":01}");
        Reject("plus sign", "{\"v\":+1}");
        Reject("fraction", "{\"v\":1.0}");
        Reject("exponent", "{\"v\":1e3}");
        Reject("uint overflow", "{\"v\":18446744073709551616}");
        Reject("huge digits overflow", "{\"v\":99999999999999999999999}");
        Reject("bare minus", "{\"v\":-}");

        // --- null and unsupported literals ---
        Reject("null value", "{\"v\":null}");
        Reject("NaN", "{\"v\":NaN}");
        Reject("True capitalized", "{\"v\":True}");

        // --- escape whitelist ---
        Reject("escape n", "{\"v\":\"\\n\"}");
        Reject("escape t", "{\"v\":\"\\t\"}");
        Reject("escape slash", "{\"v\":\"\\/\"}");
        Reject("escape b", "{\"v\":\"\\b\"}");
        Reject("uppercase hex escape", "{\"v\":\"\\u000A\"}");
        Reject("uppercase u escape", "{\"v\":\"\\U000a\"}");
        Reject("non-control unicode escape", "{\"v\":\"\\u0041\"}");
        Reject("escape above 001f", "{\"v\":\"\\u0020\"}");
        Reject("surrogate escape pair", "{\"v\":\"\\ud83d\\ude00\"}");
        Reject("unknown escape", "{\"v\":\"\\x41\"}");
        Reject("dangling backslash", "{\"v\":\"\\\"}");

        // --- raw control bytes must be rejected ---
        Reject("raw LF in string", new byte[] { 0x7b, 0x22, 0x76, 0x22, 0x3a, 0x22, 0x0a, 0x22, 0x7d });
        Reject("raw NUL in string", new byte[] { 0x7b, 0x22, 0x76, 0x22, 0x3a, 0x22, 0x00, 0x22, 0x7d });
        Reject("raw control in key", new byte[] { 0x7b, 0x22, 0x09, 0x22, 0x3a, 0x30, 0x7d });

        // --- UTF-8 strictness ---
        Reject("leading BOM", Concat(new byte[] { 0xEF, 0xBB, 0xBF }, U8.GetBytes("{}")));
        Reject("overlong slash", Wrap(new byte[] { 0xC0, 0xAF }));
        Reject("overlong NUL", Wrap(new byte[] { 0xC0, 0x80 }));
        Reject("surrogate encoded D800", Wrap(new byte[] { 0xED, 0xA0, 0x80 }));
        Reject("scalar above 10FFFF", Wrap(new byte[] { 0xF4, 0x90, 0x80, 0x80 }));
        Reject("five byte sequence", Wrap(new byte[] { 0xF8, 0x88, 0x80, 0x80, 0x80 }));
        Reject("truncated sequence", Wrap(new byte[] { 0xE2, 0x82 }));
        Reject("lone continuation byte", Wrap(new byte[] { 0x80 }));
        Reject("invalid byte FF", Wrap(new byte[] { 0xFF }));

        // --- resource bounds ---
        Accept("depth 32 accepted", Nest(32));
        Reject("depth 33 rejected", Nest(33));
        Accept("4096 members accepted", Wide(4096));
        Reject("4097 members rejected", Wide(4097));
        Accept("4096 elements accepted", WideArray(4096));
        Reject("4097 elements rejected", WideArray(4097));
        Accept("1 MiB accepted", Sized(1024 * 1024));
        Reject("over 1 MiB rejected", Sized(1024 * 1024 + 1));
        Reject("null input", (byte[])null);

        if (failures > 0) { Console.Error.WriteLine("FAILURES=" + failures); return 1; }
        Console.WriteLine("PASS RecoveryDecoderTests: 12 accept + 6 value + 60 reject + 8 bound cases");
        return 0;
    }

    static byte[] Concat(byte[] a, byte[] b) {
        var r = new byte[a.Length + b.Length];
        Buffer.BlockCopy(a, 0, r, 0, a.Length);
        Buffer.BlockCopy(b, 0, r, a.Length, b.Length);
        return r;
    }

    // {"v":"<raw>"}
    static byte[] Wrap(byte[] raw) {
        return Concat(Concat(new byte[] { 0x7b, 0x22, 0x76, 0x22, 0x3a, 0x22 }, raw), new byte[] { 0x22, 0x7d });
    }

    static string Nest(int depth) {
        var sb = new StringBuilder();
        for (int i = 1; i < depth; i++) sb.Append("{\"n\":");
        sb.Append("{}");
        for (int i = 1; i < depth; i++) sb.Append('}');
        return sb.ToString();
    }

    static string Wide(int n) {
        var sb = new StringBuilder("{");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.Append(',');
            sb.Append('"').Append('k').Append(i.ToString("D5")).Append("\":0");
        }
        return sb.Append('}').ToString();
    }

    static string WideArray(int n) {
        var sb = new StringBuilder("{\"a\":[");
        for (int i = 0; i < n; i++) { if (i > 0) sb.Append(','); sb.Append('0'); }
        return sb.Append("]}").ToString();
    }

    static string Sized(int totalBytes) {
        return "{\"v\":\"" + new string('x', totalBytes - 8) + "\"}";
    }
}
