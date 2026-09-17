using System;
using System.Collections;
using System.Collections.Generic;
using System.Reflection;
using System.Text;

// Slice 3 (behavioral): unsigned-UTF-8 key ordering, nested containers, resource bounds.
class CodecObjectTests {
    static Type type;
    static MethodInfo encode;
    static int failures;
    static readonly UTF8Encoding U8 = new UTF8Encoding(false, true);

    static byte[] Enc(IDictionary doc) {
        try { return (byte[])encode.Invoke(null, new object[] { doc }); }
        catch (TargetInvocationException e) { throw e.InnerException; }
    }

    static void Expect(string name, IDictionary doc, string expectedUtf8) {
        byte[] want = U8.GetBytes(expectedUtf8);
        byte[] got;
        try { got = Enc(doc); }
        catch (Exception e) { failures++; Console.Error.WriteLine("FAIL " + name + ": threw " + e.GetType().Name + " " + e.Message); return; }
        if (Convert.ToBase64String(got) != Convert.ToBase64String(want)) {
            failures++;
            Console.Error.WriteLine("FAIL " + name + ":\n  want " + expectedUtf8 + "\n  got  " + U8.GetString(got));
        }
    }

    static void ExpectReject(string name, IDictionary doc) {
        try { Enc(doc); failures++; Console.Error.WriteLine("FAIL " + name + ": accepted, expected ArgumentException"); }
        catch (ArgumentException) { }
        catch (Exception e) { failures++; Console.Error.WriteLine("FAIL " + name + ": wrong exception " + e.GetType().Name); }
    }

    static void ExpectAccept(string name, IDictionary doc) {
        try { Enc(doc); }
        catch (Exception e) { failures++; Console.Error.WriteLine("FAIL " + name + ": rejected " + e.GetType().Name + " " + e.Message); }
    }

    static IDictionary Map(params object[] kv) {
        // Ordered input map so the encoder cannot rely on the caller pre-sorting.
        var d = new System.Collections.Specialized.OrderedDictionary();
        for (int i = 0; i < kv.Length; i += 2) d.Add((string)kv[i], kv[i + 1]);
        return d;
    }

    static IList List(params object[] items) { return new ArrayList(items); }

    static int Main(string[] args) {
        try {
            type = Assembly.LoadFrom(args[0]).GetType("ItemGuard.Execution.CanonicalJson", true);
            encode = type.GetMethod("EncodeDocument", new Type[] { typeof(IDictionary) });
            if (encode == null) throw new MissingMethodException("CanonicalJson.EncodeDocument(IDictionary)");
        } catch (Exception e) {
            Console.Error.WriteLine("FAIL bootstrap " + e.GetType().Name + ": " + e.Message);
            return 1;
        }

        // --- key ordering: lexicographic unsigned bytes of strict UTF-8 encoded key ---
        Expect("ascii keys sorted", Map("b", (ulong)2, "a", (ulong)1, "C", (ulong)3),
            "{\"C\":3,\"a\":1,\"b\":2}");
        Expect("shorter prefix sorts first", Map("aa", (ulong)2, "a", (ulong)1, "", (ulong)0),
            "{\"\":0,\"a\":1,\"aa\":2}");
        // U+FF3A encodes EF BC BA, U+1F600 encodes F0 9F 98 80 -> astral sorts LAST by UTF-8
        // bytes but FIRST under String.CompareOrdinal (UTF-16 surrogate D83D < FF3A).
        // This vector is the mutant discriminator for the ordering rule.
        Expect("utf8 byte order not utf16 ordinal",
            Map("\U0001F600", (ulong)2, "\uFF3A", (ulong)1),
            "{\"\uFF3A\":1,\"\U0001F600\":2}");
        // U+0080 encodes C2 80; DEL U+007F stays a single 7F byte -> 7F < C2.
        Expect("multibyte after ascii", Map("\u0080", (ulong)2, "\u007f", (ulong)1),
            "{\"\u007f\":1,\"\u0080\":2}");
        // Key ordering must be on the DECODED key bytes, not on the escaped form:
        // "\u0009" escapes to backslash-u... but 09 < 41 ('A') so tab-key sorts first.
        Expect("order uses decoded key bytes", Map("A", (ulong)2, "\t", (ulong)1),
            "{\"\\u0009\":1,\"A\":2}");

        // --- nested containers ---
        Expect("empty nested object", Map("o", Map()), "{\"o\":{}}");
        Expect("empty array", Map("a", List()), "{\"a\":[]}");
        Expect("array preserves order", Map("a", List((ulong)3, (ulong)1, (ulong)2)), "{\"a\":[3,1,2]}");
        Expect("array of mixed types", Map("a", List(true, "x", (ulong)0, List(), Map())),
            "{\"a\":[true,\"x\",0,[],{}]}");
        Expect("nested object sorted independently",
            Map("z", Map("q", (ulong)1, "b", (ulong)2), "a", (ulong)0),
            "{\"a\":0,\"z\":{\"b\":2,\"q\":1}}");
        Expect("no whitespace outside strings", Map("k", "a b"), "{\"k\":\"a b\"}");

        // --- resource bounds: depth 32 inclusive (root = 1) ---
        ExpectAccept("depth 32 accepted", NestObjects(32));
        ExpectReject("depth 33 rejected", NestObjects(33));
        ExpectAccept("array depth 32 accepted", NestArrays(32));
        ExpectReject("array depth 33 rejected", NestArrays(33));

        // --- resource bounds: 4096 members / elements per container ---
        ExpectAccept("4096 members accepted", WideObject(4096));
        ExpectReject("4097 members rejected", WideObject(4097));
        ExpectAccept("4096 elements accepted", Map("a", WideArray(4096)));
        ExpectReject("4097 elements rejected", Map("a", WideArray(4097)));

        // --- resource bound: 1 MiB document cap on produced canonical bytes ---
        ExpectAccept("just under 1 MiB accepted", SizedDoc(1024 * 1024));
        ExpectReject("over 1 MiB rejected", SizedDoc(1024 * 1024 + 1));

        if (failures > 0) { Console.Error.WriteLine("FAILURES=" + failures); return 1; }
        Console.WriteLine("PASS CodecObjectTests: 11 ordering/nesting vectors + 10 bound cases");
        return 0;
    }

    static IDictionary NestObjects(int depth) {
        var inner = Map();
        for (int i = 1; i < depth; i++) inner = Map("n", inner);
        return inner;
    }

    static IDictionary NestArrays(int depth) {
        // root object is level 1; each array adds a level
        object cur = List();
        for (int i = 2; i < depth; i++) cur = List(cur);
        return Map("a", cur);
    }

    static IDictionary WideObject(int n) {
        var d = new System.Collections.Specialized.OrderedDictionary();
        for (int i = 0; i < n; i++) d.Add("k" + i.ToString("D5"), (ulong)i);
        return d;
    }

    static IList WideArray(int n) {
        var l = new ArrayList(n);
        for (int i = 0; i < n; i++) l.Add((ulong)0);
        return l;
    }

    static IDictionary SizedDoc(int totalBytes) {
        // {"v":"<pad>"} -> 8 framing bytes + pad length
        int pad = totalBytes - 8;
        return Map("v", new string('x', pad));
    }
}
