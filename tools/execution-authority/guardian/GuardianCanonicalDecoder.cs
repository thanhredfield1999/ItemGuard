using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using System.Web.Script.Serialization;

namespace ItemGuard.Execution {
    // Guardian-local parser: external receipt bytes must round-trip through the
    // guardian codec exactly. Recovery has a separately built decoder and is not
    // referenced here.
    public static class GuardianCanonicalDecoder {
        public static IDictionary ParseDocument(byte[] bytes) {
            if (bytes == null || bytes.Length == 0 || bytes.Length > CanonicalJson.MaxDocumentBytes)
                throw new FormatException("canonical document byte bound");
            string text;
            try { text = new UTF8Encoding(false, true).GetString(bytes); }
            catch (DecoderFallbackException exception) { throw new FormatException("invalid UTF-8", exception); }
            object parsed;
            try { parsed = new JavaScriptSerializer().DeserializeObject(text); }
            catch (ArgumentException exception) { throw new FormatException("invalid JSON object", exception); }
            var root = NormalizeMap(parsed);
            byte[] rebuilt;
            try { rebuilt = CanonicalJson.EncodeDocument(root); }
            catch (ArgumentException exception) { throw new FormatException("unsupported canonical receipt value", exception); }
            if (rebuilt.Length != bytes.Length) throw new FormatException("receipt bytes are not canonical");
            for (int index = 0; index < rebuilt.Length; index++)
                if (rebuilt[index] != bytes[index]) throw new FormatException("receipt bytes are not canonical");
            return root;
        }

        static IDictionary NormalizeMap(object value) {
            var source = value as IDictionary<string, object>;
            if (source == null) throw new FormatException("root must be an object");
            var result = new Hashtable();
            foreach (KeyValuePair<string, object> entry in source) result[entry.Key] = Normalize(entry.Value);
            return result;
        }
        static object Normalize(object value) {
            if (value == null) throw new FormatException("null is not canonical");
            if (value is bool || value is string || value is ulong) return value;
            if (value is byte || value is ushort || value is uint || value is int || value is long || value is decimal || value is double || value is float) {
                try {
                    decimal numeric = Convert.ToDecimal(value, CultureInfo.InvariantCulture);
                    if (numeric < 0 || numeric != Decimal.Truncate(numeric) || numeric > ulong.MaxValue)
                        throw new FormatException("integer outside UInt64 domain");
                    return (ulong)numeric;
                } catch (OverflowException exception) { throw new FormatException("integer outside UInt64 domain", exception); }
            }
            var map = value as IDictionary<string, object>;
            if (map != null) return NormalizeMap(map);
            var list = value as object[];
            if (list != null) {
                var normalized = new ArrayList();
                foreach (object member in list) normalized.Add(Normalize(member));
                return normalized;
            }
            throw new FormatException("unsupported receipt value type");
        }
    }
}
