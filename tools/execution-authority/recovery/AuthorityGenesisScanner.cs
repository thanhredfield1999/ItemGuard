using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;

namespace ItemGuard.Recovery {
    // Offline recovery slice for the fixed two-record authority prefix. It reads two
    // finite snapshots, first requires an exact common valid journal stream, then
    // classifies only sequence 0/1. It is not a runtime authorization entrypoint.
    public static class AuthorityGenesisScanner {
        const int MaximumRecords = 3;

        public static AuthorityGenesisState Scan(Stream first, Stream second, int maximumSnapshotBytes) {
            if (maximumSnapshotBytes <= 0) throw new ArgumentOutOfRangeException("maximumSnapshotBytes");
            byte[] firstBytes = ReadSnapshot(first, maximumSnapshotBytes);
            byte[] secondBytes = ReadSnapshot(second, maximumSnapshotBytes);
            using (var left = new MemoryStream(firstBytes, false))
            using (var right = new MemoryStream(secondBytes, false)) {
                JournalScanResult scan = CommonJournalScanner.Scan(left, right, MaximumRecords);
                if (scan.End != JournalScanEnd.BothEof) return AuthorityGenesisState.Invalid;
            }
            return AuthorityGenesis.Classify(ParseRecords(firstBytes));
        }

        static byte[] ReadSnapshot(Stream source, int maximumBytes) {
            if (source == null || !source.CanRead || (source.CanSeek && source.Position != 0))
                throw new ArgumentException("readable snapshot from position zero required");
            using (var result = new MemoryStream()) {
                var buffer = new byte[4096];
                while (true) {
                    int count = source.Read(buffer, 0, buffer.Length);
                    if (count == 0) return result.ToArray();
                    if (result.Length > maximumBytes - count) throw new ArgumentException("snapshot exceeds byte cap");
                    result.Write(buffer, 0, count);
                }
            }
        }

        static IDictionary[] ParseRecords(byte[] bytes) {
            if (bytes.Length == 0) return new IDictionary[0];
            var records = new List<IDictionary>();
            int start = 0;
            for (int index = 0; index < bytes.Length; index++) {
                if (bytes[index] != 10) continue;
                int length = index - start + 1;
                var line = new byte[length];
                Buffer.BlockCopy(bytes, start, line, 0, length);
                records.Add(CanonicalDecoder.VerifyJournalLine(line));
                start = index + 1;
            }
            if (start != bytes.Length) throw new FormatException("unterminated journal line");
            return records.ToArray();
        }
    }
}
