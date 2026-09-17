using System;
using System.Collections;

namespace ItemGuard.Recovery {
    // Pure trusted-prefix classifier. It receives already decoded records from the
    // common exact mirror prefix; it does not validate journal bytes or authorize work.
    public enum AuthorityGenesisState { Empty, AuthorityReadyOnly, AuthorizationConsumed, Invalid }

    public static class AuthorityGenesis {
        public static AuthorityGenesisState Classify(IDictionary[] records) {
            if (records == null) throw new ArgumentNullException("records");
            if (records.Length == 0) return AuthorityGenesisState.Empty;
            string runToken;
            if (!Matches(records[0], 0, "AUTHORITY_READY", null, out runToken)) return AuthorityGenesisState.Invalid;
            if (records.Length == 1) return AuthorityGenesisState.AuthorityReadyOnly;
            string consumedToken;
            if (!Matches(records[1], 1, "AUTHORIZATION_CONSUMED", runToken, out consumedToken))
                return AuthorityGenesisState.Invalid;
            return records.Length == 2 ? AuthorityGenesisState.AuthorizationConsumed : AuthorityGenesisState.Invalid;
        }

        static bool Matches(IDictionary record, ulong sequence, string eventName, string requiredToken, out string token) {
            token = null;
            // Journal decoding retains chain/hash and provenance fields. This narrow
            // classifier verifies only the authority-prefix fields; the sealed full
            // journal schema remains a separate recovery gate.
            if (record == null
                || !(record["seq"] is ulong) || (ulong)record["seq"] != sequence
                || !(record["event"] is string) || (string)record["event"] != eventName
                || !(record["runToken"] is string)) return false;
            token = (string)record["runToken"];
            Guid parsed;
            if (!Guid.TryParseExact(token, "D", out parsed) || token != parsed.ToString("D")
                || token[14] != '4' || "89ab".IndexOf(token[19]) < 0) return false;
            return requiredToken == null || token == requiredToken;
        }
    }
}
