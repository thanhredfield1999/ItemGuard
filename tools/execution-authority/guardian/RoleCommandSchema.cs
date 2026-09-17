using System;
using System.Collections.Generic;

namespace ItemGuard.Execution {
    // Pure typed role-vector expansion; it neither resolves PATH nor launches processes.
    public static class RoleCommandSchema {
        public static string[] Expand(string role, IDictionary<string,string> fields) {
            if (role != "PIPELINE") throw new FormatException("unknown role");
            if (fields == null || fields.Count != 2 || !fields.ContainsKey("script") || !fields.ContainsKey("runToken")) throw new FormatException("exact PIPELINE fields required");
            string script = fields["script"], token = fields["runToken"];
            if (script != "C:/tool root/run.py") throw new FormatException("unallowlisted pipeline script");
            Guid parsed;
            if (!Guid.TryParseExact(token, "D", out parsed) || token != parsed.ToString("D")
                || token[14] != '4' || "89ab".IndexOf(token[19]) < 0)
                throw new FormatException("runToken must be canonical UUID v4 form");
            return new[] { "C:/tool root/python.exe", script, "--run-token=" + token };
        }
    }
}
