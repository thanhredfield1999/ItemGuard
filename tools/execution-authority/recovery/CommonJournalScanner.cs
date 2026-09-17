using System.IO;

namespace ItemGuard.Recovery {
    public enum JournalScanEnd { BothEof, InvalidSuffix, ReadFailure, RecordLimit }
    public sealed class JournalScanResult {
        public int CommonRecordCount { get; private set; }
        public JournalScanEnd End { get; private set; }
        internal JournalScanResult(int count, JournalScanEnd end) { CommonRecordCount = count; End = end; }
    }

    // Finite immutable snapshots from position zero, blocking reads only. Caller owns streams.
    // Byte/hash/seq evidence, NEVER schema/genesis/lifecycle authority or historyComplete.
    public static class CommonJournalScanner {
        sealed class LineReader {
            readonly Stream source;
            readonly byte[] chunk = new byte[4096];
            readonly byte[] line = new byte[CanonicalDecoder.MaxDocumentBytes];
            int start, end;
            public LineReader(Stream source) { this.source = source; }
            public bool HasByte() {
                if (start < end) return true;
                end = source.Read(chunk, 0, chunk.Length); start = 0;
                return end != 0;
            }
            public byte[] NextLine() {
                if (!HasByte()) return null;
                int length = 0;
                do {
                    byte value = chunk[start++];
                    line[length++] = value;
                    if (value == 10) {
                        byte[] result = new byte[length];
                        System.Buffer.BlockCopy(line, 0, result, 0, length);
                        return result;
                    }
                } while (length < line.Length && HasByte());
                return new byte[0]; // Unterminated or over cap: never a clean EOF.
            }
        }
        public static JournalScanResult Scan(Stream first, Stream second, int maximumRecords) {
            var chain = new CommonJournalChain(maximumRecords);
            if (first == null || second == null || object.ReferenceEquals(first, second)
                || !first.CanRead || !second.CanRead
                || (first.CanSeek && first.Position != 0) || (second.CanSeek && second.Position != 0))
                throw new System.ArgumentException("distinct readable snapshots from position zero required");
            var left = new LineReader(first);
            var right = new LineReader(second);
            while (true) {
                try {
                    if (chain.CommonRecordCount == maximumRecords) {
                        bool moreLeft = left.HasByte(), moreRight = right.HasByte();
                        return new JournalScanResult(chain.CommonRecordCount,
                            moreLeft || moreRight ? JournalScanEnd.RecordLimit : JournalScanEnd.BothEof);
                    }
                    byte[] a = left.NextLine(), b = right.NextLine();
                    if (a == null && b == null) return new JournalScanResult(chain.CommonRecordCount, JournalScanEnd.BothEof);
                    if (!chain.AppendPair(a, b)) return new JournalScanResult(chain.CommonRecordCount, JournalScanEnd.InvalidSuffix);
                } catch (IOException) {
                    return new JournalScanResult(chain.CommonRecordCount, JournalScanEnd.ReadFailure);
                }
            }
        }
    }
}
