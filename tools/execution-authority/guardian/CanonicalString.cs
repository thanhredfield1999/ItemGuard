using System;
using System.Globalization;
using System.Text;

namespace ItemGuard.Execution {
    public static class CanonicalString {
        public static byte[] Encode(string value) {
            if (value == null) throw new ArgumentNullException("value");
            var output = new StringBuilder();
            AppendEncoded(output, value);
            return new UTF8Encoding(false, true).GetBytes(output.ToString());
        }

        /// <summary>Appends the canonical JSON string form (including quotes) of
        /// <paramref name="value"/>. Escapes only quote, backslash and U+0000..001F
        /// as lowercase \u00xx; every other scalar stays raw.</summary>
        public static void AppendEncoded(StringBuilder output, string value) {
            if (value == null) throw new ArgumentNullException("value");
            output.Append('"');
            foreach (char c in value) {
                if (c == '"' || c == '\\') output.Append('\\').Append(c);
                else if (c < 32) output.Append("\\u00").Append(((int)c).ToString("x2", CultureInfo.InvariantCulture));
                else output.Append(c);
            }
            output.Append('"');
        }
    }
}
