namespace ItemGuard.Recovery {
    // Byte/hash/sequence layer only, not trusted genesis, schema, lifecycle or historyComplete.
    // Caller supplies one complete line from EACH mirror in order; never filter/reorder.
    public sealed class CommonJournalChain {
        readonly int maximumRecords;
        string previousLineHash = new string('0', 64);
        public int CommonRecordCount { get; private set; }
        public bool Stopped { get; private set; }
        public CommonJournalChain(int maximumRecords) {
            if (maximumRecords <= 0) throw new System.ArgumentOutOfRangeException("maximumRecords");
            this.maximumRecords = maximumRecords;
        }
        bool Stop() { Stopped = true; return false; }
        public bool AppendPair(byte[] first, byte[] second) {
            if (Stopped) return false;
            if (first == null || second == null || CommonRecordCount >= maximumRecords
                || first.Length == 0 || first.Length > CanonicalDecoder.MaxDocumentBytes
                || first.Length != second.Length) return Stop();
            for (int i = 0; i < first.Length; i++) if (first[i] != second[i]) return Stop();
            try {
                var record = CanonicalDecoder.VerifyJournalLine(first);
                if (!(record["seq"] is ulong) || (ulong)record["seq"] != (ulong)CommonRecordCount
                    || !(record["previousRecordSha256"] is string)
                    || (string)record["previousRecordSha256"] != previousLineHash) return Stop();
                // Hash exact complete prior line bytes, including LF; no retained array alias.
                using (var sha = System.Security.Cryptography.SHA256.Create()) {
                    var text = new System.Text.StringBuilder(64);
                    foreach (byte b in sha.ComputeHash(first)) text.Append(b.ToString("x2"));
                    previousLineHash = text.ToString();
                }
                CommonRecordCount++;
                return true;
            } catch (System.FormatException) { return Stop(); }
        }
    }
}
