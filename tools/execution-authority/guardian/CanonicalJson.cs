using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace ItemGuard.Execution {
    /// <summary>
    /// Guardian-side canonical JSON v1 encoder. Pure in-memory, no I/O, no runtime or
    /// process dependency. Deliberately independent from the recovery decoder assembly.
    ///
    /// Codec v1: UTF-8 without BOM; root must be an object; members sorted by
    /// lexicographic unsigned bytes of the strict-UTF-8 encoded key (shorter prefix
    /// first); duplicate keys rejected; escapes limited to \" \\ and lowercase \u00xx
    /// for U+0000..001F; arrays keep order; value domain is bool, string, object,
    /// array and UInt64 only; no whitespace outside strings; no trailing LF.
    /// </summary>
    public static class CanonicalJson {
        public const int MaxDepth = 32;
        public const int MaxContainerEntries = 4096;
        public const int MaxDocumentBytes = 1024 * 1024;
        public const int MaxFrameBytes = 64 * 1024;

        static readonly UTF8Encoding Utf8 = new UTF8Encoding(false, true);

        public static byte[] EncodeDocument(IDictionary root) {
            return EncodeDocument(root, MaxDocumentBytes);
        }

        public const string RecordHashMember = "recordSha256";

        /// <summary>SHA-256 of the canonical encoding of <paramref name="body"/> after
        /// deleting exactly the top-level <c>recordSha256</c> member. No placeholder, no
        /// LF; nested members of the same name are preserved.</summary>
        public static string ComputeRecordSha256(IDictionary body) {
            if (body == null) throw new ArgumentException("record body required");
            var preImage = new System.Collections.Specialized.OrderedDictionary();
            foreach (DictionaryEntry e in body) {
                var key = e.Key as string;
                if (key == null) throw new ArgumentException("member name must be a string");
                if (key == RecordHashMember) continue;
                preImage.Add(key, e.Value);
            }
            return Hex(EncodeDocument(preImage));
        }

        /// <summary>Canonical record bytes plus exactly one terminating LF. The record must
        /// already carry a lowercase hex-64 <c>recordSha256</c> matching its own pre-image;
        /// a body without it is rejected rather than silently hashed.</summary>
        public static byte[] EncodeJournalLine(IDictionary record) {
            if (record == null) throw new ArgumentException("record required");
            object declared = null;
            bool present = false;
            foreach (DictionaryEntry e in record) {
                if ((e.Key as string) == RecordHashMember) { declared = e.Value; present = true; break; }
            }
            if (!present) throw new ArgumentException("record is missing " + RecordHashMember);
            var hex = declared as string;
            if (!IsLowerHex64(hex)) throw new ArgumentException(RecordHashMember + " must be lowercase hex-64");
            if (hex != ComputeRecordSha256(record))
                throw new ArgumentException(RecordHashMember + " does not match record pre-image");

            byte[] bodyBytes = EncodeDocument(record, MaxDocumentBytes - 1);
            var line = new byte[bodyBytes.Length + 1];
            Buffer.BlockCopy(bodyBytes, 0, line, 0, bodyBytes.Length);
            line[bodyBytes.Length] = 0x0A;
            return line;
        }

        public static bool IsLowerHex64(string value) {
            if (value == null || value.Length != 64) return false;
            foreach (char c in value) {
                bool ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
                if (!ok) return false;
            }
            return true;
        }

        static string Hex(byte[] data) {
            using (var sha = System.Security.Cryptography.SHA256.Create()) {
                byte[] h = sha.ComputeHash(data);
                var sb = new StringBuilder(64);
                foreach (byte x in h) sb.Append(x.ToString("x2", CultureInfo.InvariantCulture));
                return sb.ToString();
            }
        }

        /// <summary>Control-frame pre-image: identical codec, tighter 64 KiB cap.</summary>
        public static byte[] EncodeFrameBody(IDictionary root) {
            return EncodeDocument(root, MaxFrameBytes);
        }

        public static byte[] EncodeDocument(IDictionary root, int maxBytes) {
            if (root == null) throw new ArgumentException("root object required");
            if (maxBytes <= 0) throw new ArgumentException("maxBytes must be positive");
            var sb = new StringBuilder();
            WriteObject(sb, root, 1, maxBytes);
            byte[] bytes = Utf8.GetBytes(sb.ToString());
            if (bytes.Length > maxBytes)
                throw new ArgumentException("document exceeds " + maxBytes + " byte cap");
            return bytes;
        }

        static void CheckBudget(StringBuilder sb, int maxBytes) {
            // Cheap pre-allocation guard: UTF-16 length is a lower bound on UTF-8 byte
            // length for BMP-heavy content, so this trips long before memory blows up.
            // The exact byte-length check still runs on the finished document.
            if (sb.Length > maxBytes)
                throw new ArgumentException("document exceeds " + maxBytes + " byte cap");
        }

        static void WriteObject(StringBuilder sb, IDictionary map, int depth, int maxBytes) {
            if (depth > MaxDepth) throw new ArgumentException("nesting exceeds depth " + MaxDepth);
            if (map.Count > MaxContainerEntries)
                throw new ArgumentException("object exceeds " + MaxContainerEntries + " members");

            var members = new List<Member>(map.Count);
            foreach (DictionaryEntry entry in map) {
                var key = entry.Key as string;
                if (key == null) throw new ArgumentException("member name must be a string");
                members.Add(new Member(key, Utf8.GetBytes(key), entry.Value));
            }
            members.Sort(Member.CompareKeyBytes);
            for (int i = 1; i < members.Count; i++) {
                if (Member.CompareKeyBytes(members[i - 1], members[i]) == 0)
                    throw new ArgumentException("duplicate member name");
            }

            sb.Append('{');
            for (int i = 0; i < members.Count; i++) {
                if (i > 0) sb.Append(',');
                CanonicalString.AppendEncoded(sb, members[i].Key);
                sb.Append(':');
                WriteValue(sb, members[i].Value, depth, maxBytes);
                CheckBudget(sb, maxBytes);
            }
            sb.Append('}');
        }

        static void WriteArray(StringBuilder sb, IList list, int depth, int maxBytes) {
            if (depth > MaxDepth) throw new ArgumentException("nesting exceeds depth " + MaxDepth);
            if (list.Count > MaxContainerEntries)
                throw new ArgumentException("array exceeds " + MaxContainerEntries + " elements");
            sb.Append('[');
            for (int i = 0; i < list.Count; i++) {
                if (i > 0) sb.Append(',');
                WriteValue(sb, list[i], depth, maxBytes);
                CheckBudget(sb, maxBytes);
            }
            sb.Append(']');
        }

        static void WriteValue(StringBuilder sb, object value, int depth, int maxBytes) {
            if (value == null) throw new ArgumentException("null is not part of codec v1");
            if (value is bool) { sb.Append(((bool)value) ? "true" : "false"); return; }
            if (value is ulong) { sb.Append(((ulong)value).ToString(CultureInfo.InvariantCulture)); return; }
            var s = value as string;
            if (s != null) { CanonicalString.AppendEncoded(sb, s); return; }
            var map = value as IDictionary;
            if (map != null) { WriteObject(sb, map, depth + 1, maxBytes); return; }
            var list = value as IList;
            if (list != null) { WriteArray(sb, list, depth + 1, maxBytes); return; }
            throw new ArgumentException("unsupported value type " + value.GetType().FullName);
        }

        sealed class Member {
            public readonly string Key;
            public readonly byte[] KeyBytes;
            public readonly object Value;
            public Member(string key, byte[] keyBytes, object value) {
                Key = key; KeyBytes = keyBytes; Value = value;
            }
            public static int CompareKeyBytes(Member a, Member b) {
                byte[] x = a.KeyBytes, y = b.KeyBytes;
                int n = x.Length < y.Length ? x.Length : y.Length;
                for (int i = 0; i < n; i++) {
                    if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
                }
                return x.Length.CompareTo(y.Length);
            }
        }
    }
}
