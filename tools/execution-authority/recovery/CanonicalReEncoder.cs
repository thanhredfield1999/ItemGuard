using System;
using System.Collections;
using System.Globalization;
using System.Text;

namespace ItemGuard.Recovery {
    /// <summary>
    /// Recovery-side canonical re-encoder. The verifier must rebuild canonical bytes from
    /// a parsed object and compare them to the input for exact equality before hashing, so
    /// recovery needs its own serializer — it must not import the guardian encoder.
    /// Stage 1: emit members in the order supplied.
    /// </summary>
    public static class CanonicalReEncoder {
        public static byte[] Encode(IDictionary root) {
            if (root == null) throw new FormatException("root object required");
            var sb = new StringBuilder();
            WriteObject(sb, root);
            return new UTF8Encoding(false, true).GetBytes(sb.ToString());
        }

        static void WriteObject(StringBuilder sb, IDictionary map) {
            var utf8 = new UTF8Encoding(false, true);
            var keys = new System.Collections.Generic.List<string>();
            foreach (DictionaryEntry e in map) {
                var k = e.Key as string;
                if (k == null) throw new FormatException("member name must be a string");
                keys.Add(k);
            }
            keys.Sort(delegate(string a, string b) {
                byte[] x = utf8.GetBytes(a), y = utf8.GetBytes(b);
                int n = x.Length < y.Length ? x.Length : y.Length;
                for (int i = 0; i < n; i++) { if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1; }
                return x.Length.CompareTo(y.Length);
            });
            sb.Append('{');
            for (int i = 0; i < keys.Count; i++) {
                if (i > 0) sb.Append(',');
                WriteString(sb, keys[i]);
                sb.Append(':');
                WriteValue(sb, map[keys[i]]);
            }
            sb.Append('}');
        }

        static void WriteArray(StringBuilder sb, IList list) {
            sb.Append('[');
            for (int i = 0; i < list.Count; i++) {
                if (i > 0) sb.Append(',');
                WriteValue(sb, list[i]);
            }
            sb.Append(']');
        }

        static void WriteValue(StringBuilder sb, object v) {
            if (v == null) throw new FormatException("null is not part of codec v1");
            if (v is bool) { sb.Append(((bool)v) ? "true" : "false"); return; }
            if (v is ulong) { sb.Append(((ulong)v).ToString(CultureInfo.InvariantCulture)); return; }
            var s = v as string;
            if (s != null) { WriteString(sb, s); return; }
            var m = v as IDictionary;
            if (m != null) { WriteObject(sb, m); return; }
            var l = v as IList;
            if (l != null) { WriteArray(sb, l); return; }
            throw new FormatException("unsupported value type " + v.GetType().FullName);
        }

        static void WriteString(StringBuilder sb, string value) {
            sb.Append('"');
            foreach (char c in value) {
                if (c == '"' || c == '\\') sb.Append('\\').Append(c);
                else if (c < 32) sb.Append("\\u00").Append(((int)c).ToString("x2", CultureInfo.InvariantCulture));
                else sb.Append(c);
            }
            sb.Append('"');
        }
    }
}
