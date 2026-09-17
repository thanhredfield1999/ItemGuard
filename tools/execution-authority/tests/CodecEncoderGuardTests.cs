using System;
using System.Collections;
using System.Collections.Generic;
using System.Reflection;
using System.Text;

// Slice 4 (behavioral): encoder duplicate-member rejection and the 64 KiB frame cap.
class CodecEncoderGuardTests {
    static Type type;
    static MethodInfo encodeDoc;
    static MethodInfo encodeFrame;
    static int failures;

    static int Main(string[] args) {
        try {
            type = Assembly.LoadFrom(args[0]).GetType("ItemGuard.Execution.CanonicalJson", true);
            encodeDoc = type.GetMethod("EncodeDocument", new Type[] { typeof(IDictionary) });
            encodeFrame = type.GetMethod("EncodeFrameBody", new Type[] { typeof(IDictionary) });
            if (encodeDoc == null) throw new MissingMethodException("EncodeDocument(IDictionary)");
            if (encodeFrame == null) throw new MissingMethodException("EncodeFrameBody(IDictionary)");
        } catch (Exception e) {
            Console.Error.WriteLine("FAIL bootstrap " + e.GetType().Name + ": " + e.Message);
            return 1;
        }

        // A hostile/careless caller can hand the encoder a non-unique member list.
        // The encoder must reject it before sorting, never silently emit both or
        // last-write-wins.
        ExpectReject(encodeDoc, "duplicate members rejected", DuplicateKeyMap("a", "a"));
        ExpectReject(encodeDoc, "duplicate members rejected nested",
            Single("o", DuplicateKeyMap("x", "x")));
        ExpectAccept(encodeDoc, "distinct members accepted", DuplicateKeyMap("a", "b"));

        // Frame surface: identical codec, 64 KiB cap instead of 1 MiB.
        ExpectAccept(encodeFrame, "frame at 64 KiB accepted", SizedDoc(64 * 1024));
        ExpectReject(encodeFrame, "frame over 64 KiB rejected", SizedDoc(64 * 1024 + 1));
        ExpectAccept(encodeDoc, "same doc fine on 1 MiB surface", SizedDoc(64 * 1024 + 1));

        // Frame output must be byte-identical to the document encoder for the same value.
        var probe = Single("k", "v");
        try {
            byte[] a = (byte[])encodeDoc.Invoke(null, new object[] { probe });
            byte[] b = (byte[])encodeFrame.Invoke(null, new object[] { probe });
            if (Convert.ToBase64String(a) != Convert.ToBase64String(b)) {
                failures++; Console.Error.WriteLine("FAIL frame/document byte divergence");
            }
            if (Encoding.UTF8.GetString(b) != "{\"k\":\"v\"}") {
                failures++; Console.Error.WriteLine("FAIL frame body form: " + Encoding.UTF8.GetString(b));
            }
        } catch (Exception e) {
            failures++; Console.Error.WriteLine("FAIL frame parity: " + e.GetType().Name);
        }

        if (failures > 0) { Console.Error.WriteLine("FAILURES=" + failures); return 1; }
        Console.WriteLine("PASS CodecEncoderGuardTests: 3 duplicate cases + 3 frame-cap cases + parity");
        return 0;
    }

    static void ExpectReject(MethodInfo m, string name, IDictionary doc) {
        try {
            m.Invoke(null, new object[] { doc });
            failures++; Console.Error.WriteLine("FAIL " + name + ": accepted, expected ArgumentException");
        } catch (TargetInvocationException e) {
            if (!(e.InnerException is ArgumentException)) {
                failures++; Console.Error.WriteLine("FAIL " + name + ": wrong exception " + e.InnerException.GetType().Name);
            }
        }
    }

    static void ExpectAccept(MethodInfo m, string name, IDictionary doc) {
        try { m.Invoke(null, new object[] { doc }); }
        catch (TargetInvocationException e) {
            failures++; Console.Error.WriteLine("FAIL " + name + ": rejected " + e.InnerException.GetType().Name + " " + e.InnerException.Message);
        }
    }

    static IDictionary Single(string k, object v) {
        var d = new System.Collections.Specialized.OrderedDictionary();
        d.Add(k, v);
        return d;
    }

    /// <summary>A minimal IDictionary that enumerates the two supplied member names
    /// verbatim, so a duplicate can actually reach the encoder.</summary>
    sealed class RawMemberList : IDictionary {
        readonly string[] keys;
        public RawMemberList(string k1, string k2) { keys = new string[] { k1, k2 }; }
        public IDictionaryEnumerator GetEnumerator() { return new E(keys); }
        IEnumerator IEnumerable.GetEnumerator() { return GetEnumerator(); }
        public int Count { get { return keys.Length; } }
        public bool IsFixedSize { get { return true; } }
        public bool IsReadOnly { get { return true; } }
        public bool IsSynchronized { get { return false; } }
        public object SyncRoot { get { return this; } }
        public ICollection Keys { get { return keys; } }
        public ICollection Values { get { return new object[] { (ulong)1, (ulong)2 }; } }
        public object this[object key] { get { return (ulong)1; } set { throw new NotSupportedException(); } }
        public void Add(object k, object v) { throw new NotSupportedException(); }
        public void Clear() { throw new NotSupportedException(); }
        public bool Contains(object key) { return Array.IndexOf(keys, key as string) >= 0; }
        public void CopyTo(Array array, int index) { throw new NotSupportedException(); }
        public void Remove(object key) { throw new NotSupportedException(); }

        sealed class E : IDictionaryEnumerator {
            readonly string[] keys; int i = -1;
            public E(string[] k) { keys = k; }
            public bool MoveNext() { i++; return i < keys.Length; }
            public void Reset() { i = -1; }
            public object Current { get { return Entry; } }
            public DictionaryEntry Entry { get { return new DictionaryEntry(keys[i], (ulong)(i + 1)); } }
            public object Key { get { return keys[i]; } }
            public object Value { get { return (ulong)(i + 1); } }
        }
    }

    static IDictionary DuplicateKeyMap(string k1, string k2) { return new RawMemberList(k1, k2); }

    static IDictionary SizedDoc(int totalBytes) {
        // {"v":"<pad>"} -> 8 framing bytes + pad length
        return Single("v", new string('x', totalBytes - 8));
    }
}
