using System;
using System.Collections;
using System.Collections.Generic;
using System.Text;

namespace ItemGuard.Recovery {
    /// <summary>
    /// Recovery-side canonical JSON v1 decoder/validator. Written independently of the
    /// guardian encoder: separate assembly, separate source, no shared helper.
    ///
    /// It is a validator first and a parser second: it accepts only byte sequences that
    /// are already in exact canonical form. No whitespace anywhere outside strings, root
    /// must be an object, members strictly increasing by unsigned UTF-8 key bytes,
    /// duplicates rejected, escape whitelist limited to \" \\ and lowercase \u00xx for
    /// U+0000..001F, raw control bytes rejected, strict UTF-8 (no BOM, no overlong, no
    /// surrogate encoding, nothing above U+10FFFF), integers are shortest-form decimal in
    /// [0, 2^64-1], and no null. Bounds are checked before allocation/recursion.
    /// </summary>
    public static class CanonicalDecoder {
        public const int MaxDepth = 32;
        public const int MaxContainerEntries = 4096;
        public const int MaxDocumentBytes = 1024 * 1024;

        public const string RecordHashMember = "recordSha256";

        /// <summary>SHA-256 over the recovery-side canonical re-encoding of the body with
        /// exactly the top-level <c>recordSha256</c> member deleted.</summary>
        public static string ComputeRecordSha256(IDictionary body) {
            if (body == null) throw new FormatException("record body required");
            var preImage = new System.Collections.Specialized.OrderedDictionary();
            foreach (DictionaryEntry e in body) {
                var key = e.Key as string;
                if (key == null) throw new FormatException("member name must be a string");
                if (key == RecordHashMember) continue;
                preImage.Add(key, e.Value);
            }
            return Hex(CanonicalReEncoder.Encode(preImage));
        }

        /// <summary>Verifies one journal line: exactly one terminating LF, strict canonical
        /// body, canonical round-trip byte equality, a lowercase hex-64 <c>recordSha256</c>
        /// member, and a hash matching the deletion pre-image.</summary>
        public static IDictionary VerifyJournalLine(byte[] line) {
            if (line == null) throw new FormatException("line required");
            // Journal document cap includes its LF, matching the independent writer.
            // Reject before allocating/copying the body.
            if (line.Length > MaxDocumentBytes) throw new FormatException("journal line exceeds byte cap");
            if (line.Length < 2) throw new FormatException("line too short");
            if (line[line.Length - 1] != 0x0A) throw new FormatException("line must end with exactly one LF");
            if (line[line.Length - 2] == 0x0A) throw new FormatException("line must end with exactly one LF");
            if (line[line.Length - 2] == 0x0D) throw new FormatException("CR is not permitted");

            var body = new byte[line.Length - 1];
            Buffer.BlockCopy(line, 0, body, 0, body.Length);
            for (int k = 0; k < body.Length; k++) {
                if (body[k] == 0x0A) throw new FormatException("embedded LF in record body");
            }

            IDictionary record = ParseDocument(body);

            // Round-trip: rebuild canonical bytes and demand exact equality before hashing.
            byte[] rebuilt = CanonicalReEncoder.Encode(record);
            if (rebuilt.Length != body.Length) throw new FormatException("record is not exact canonical bytes");
            for (int k = 0; k < body.Length; k++) {
                if (rebuilt[k] != body[k]) throw new FormatException("record is not exact canonical bytes");
            }

            object declared = record.Contains(RecordHashMember) ? record[RecordHashMember] : null;
            if (declared == null) throw new FormatException("record is missing " + RecordHashMember);
            var hex = declared as string;
            if (!IsLowerHex64(hex)) throw new FormatException(RecordHashMember + " must be lowercase hex-64");
            if (hex != ComputeRecordSha256(record)) throw new FormatException("record hash mismatch");
            return record;
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
                foreach (byte x in h) sb.Append(x.ToString("x2", System.Globalization.CultureInfo.InvariantCulture));
                return sb.ToString();
            }
        }

        public static IDictionary ParseDocument(byte[] input) {
            return ParseDocument(input, MaxDocumentBytes);
        }

        public static IDictionary ParseDocument(byte[] input, int maxBytes) {
            if (input == null) throw new FormatException("input required");
            if (input.Length == 0) throw new FormatException("empty input");
            if (input.Length > maxBytes)
                throw new FormatException("document exceeds " + maxBytes + " byte cap");
            var r = new Reader(input);
            if (r.Peek() != (byte)'{') throw new FormatException("root must be an object");
            IDictionary root = r.ReadObject(1);
            if (!r.AtEnd) throw new FormatException("trailing bytes after root object");
            return root;
        }

        sealed class Reader {
            readonly byte[] b;
            int i;

            public Reader(byte[] bytes) { b = bytes; i = 0; }
            public bool AtEnd { get { return i >= b.Length; } }

            public byte Peek() {
                if (i >= b.Length) throw new FormatException("unexpected end of input");
                return b[i];
            }

            byte Next() { byte v = Peek(); i++; return v; }

            void Expect(byte c) {
                if (Next() != c) throw new FormatException("expected '" + (char)c + "'");
            }

            public IDictionary ReadObject(int depth) {
                if (depth > MaxDepth) throw new FormatException("nesting exceeds depth " + MaxDepth);
                Expect((byte)'{');
                var map = new Dictionary<string, object>(StringComparer.Ordinal);
                if (Peek() == (byte)'}') { i++; return map; }

                byte[] previousKeyBytes = null;
                int count = 0;
                while (true) {
                    if (++count > MaxContainerEntries)
                        throw new FormatException("object exceeds " + MaxContainerEntries + " members");
                    if (Peek() != (byte)'"') throw new FormatException("member name must be a string");
                    byte[] keyBytes;
                    string key = ReadString(out keyBytes);
                    if (previousKeyBytes != null) {
                        int cmp = CompareUnsigned(previousKeyBytes, keyBytes);
                        if (cmp == 0) throw new FormatException("duplicate member name");
                        if (cmp > 0) throw new FormatException("members not in canonical key order");
                    }
                    previousKeyBytes = keyBytes;
                    Expect((byte)':');
                    map[key] = ReadValue(depth);
                    byte c = Next();
                    if (c == (byte)',') continue;
                    if (c == (byte)'}') return map;
                    throw new FormatException("expected ',' or '}'");
                }
            }

            IList ReadArray(int depth) {
                if (depth > MaxDepth) throw new FormatException("nesting exceeds depth " + MaxDepth);
                Expect((byte)'[');
                var list = new ArrayList();
                if (Peek() == (byte)']') { i++; return list; }
                int count = 0;
                while (true) {
                    if (++count > MaxContainerEntries)
                        throw new FormatException("array exceeds " + MaxContainerEntries + " elements");
                    list.Add(ReadValue(depth));
                    byte c = Next();
                    if (c == (byte)',') continue;
                    if (c == (byte)']') return list;
                    throw new FormatException("expected ',' or ']'");
                }
            }

            object ReadValue(int depth) {
                byte c = Peek();
                if (c == (byte)'{') return ReadObject(depth + 1);
                if (c == (byte)'[') return ReadArray(depth + 1);
                if (c == (byte)'"') { byte[] ignored; return ReadString(out ignored); }
                if (c == (byte)'t') { Literal("true"); return true; }
                if (c == (byte)'f') { Literal("false"); return false; }
                if (c >= (byte)'0' && c <= (byte)'9') return ReadUInt64();
                throw new FormatException("unsupported value token");
            }

            void Literal(string word) {
                for (int k = 0; k < word.Length; k++) {
                    if (Next() != (byte)word[k]) throw new FormatException("bad literal");
                }
            }

            ulong ReadUInt64() {
                int start = i;
                while (i < b.Length && b[i] >= (byte)'0' && b[i] <= (byte)'9') i++;
                int len = i - start;
                if (len == 0) throw new FormatException("expected digits");
                if (len > 1 && b[start] == (byte)'0') throw new FormatException("leading zero");
                if (len > 20) throw new FormatException("integer out of UInt64 range");
                ulong value = 0;
                checked {
                    try {
                        for (int k = start; k < i; k++) value = value * 10 + (ulong)(b[k] - (byte)'0');
                    } catch (OverflowException) {
                        throw new FormatException("integer out of UInt64 range");
                    }
                }
                return value;
            }

            /// <summary>Reads a canonical string; <paramref name="decodedBytes"/> receives the
            /// strict-UTF-8 bytes of the decoded scalars, which is the key-ordering domain.</summary>
            string ReadString(out byte[] decodedBytes) {
                Expect((byte)'"');
                var acc = new List<byte>();
                while (true) {
                    byte c = Next();
                    if (c == (byte)'"') break;
                    if (c == (byte)'\\') {
                        byte e = Next();
                        if (e == (byte)'"' || e == (byte)'\\') { acc.Add(e); continue; }
                        if (e != (byte)'u') throw new FormatException("escape outside whitelist");
                        if (i + 4 > b.Length) throw new FormatException("truncated \\u escape");
                        if (b[i] != (byte)'0' || b[i + 1] != (byte)'0')
                            throw new FormatException("only U+0000..001F may be escaped");
                        int hi = LowerHex(b[i + 2]);
                        int lo = LowerHex(b[i + 3]);
                        int scalar = hi * 16 + lo;
                        if (scalar > 0x1F) throw new FormatException("only U+0000..001F may be escaped");
                        i += 4;
                        acc.Add((byte)scalar);
                        continue;
                    }
                    if (c < 0x20) throw new FormatException("raw control byte in string");
                    if (c < 0x80) { acc.Add(c); continue; }
                    ReadUtf8Sequence(c, acc);
                }
                decodedBytes = acc.ToArray();
                return new UTF8Encoding(false, true).GetString(decodedBytes);
            }

            static int LowerHex(byte c) {
                if (c >= (byte)'0' && c <= (byte)'9') return c - (byte)'0';
                if (c >= (byte)'a' && c <= (byte)'f') return c - (byte)'a' + 10;
                throw new FormatException("\\u escape must use lowercase hex");
            }

            /// <summary>Validates one multi-byte UTF-8 sequence: rejects overlong forms,
            /// surrogate-range encodings, scalars above U+10FFFF and truncated tails.</summary>
            void ReadUtf8Sequence(byte lead, List<byte> acc) {
                int need;
                int scalar;
                if (lead >= 0xC2 && lead <= 0xDF) { need = 1; scalar = lead & 0x1F; }
                else if (lead >= 0xE0 && lead <= 0xEF) { need = 2; scalar = lead & 0x0F; }
                else if (lead >= 0xF0 && lead <= 0xF4) { need = 3; scalar = lead & 0x07; }
                else throw new FormatException("invalid UTF-8 lead byte");

                var seq = new byte[need + 1];
                seq[0] = lead;
                for (int k = 0; k < need; k++) {
                    byte cont = Next();
                    if ((cont & 0xC0) != 0x80) throw new FormatException("invalid UTF-8 continuation byte");
                    scalar = (scalar << 6) | (cont & 0x3F);
                    seq[k + 1] = cont;
                }
                if (need == 2 && scalar < 0x800) throw new FormatException("overlong UTF-8 sequence");
                if (need == 3 && scalar < 0x10000) throw new FormatException("overlong UTF-8 sequence");
                if (scalar >= 0xD800 && scalar <= 0xDFFF)
                    throw new FormatException("surrogate scalar encoded in UTF-8");
                if (scalar > 0x10FFFF) throw new FormatException("scalar above U+10FFFF");
                acc.AddRange(seq);
            }

            static int CompareUnsigned(byte[] x, byte[] y) {
                int n = x.Length < y.Length ? x.Length : y.Length;
                for (int k = 0; k < n; k++) {
                    if (x[k] != y[k]) return x[k] < y[k] ? -1 : 1;
                }
                return x.Length.CompareTo(y.Length);
            }
        }
    }
}
