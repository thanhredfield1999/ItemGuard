namespace ItemGuard.Execution.Control {
    // One length-prefixed frame, no stream/OS access and no reset/reuse.
    public sealed class ControlFrameReader {
        readonly byte[] header = new byte[4];
        int headerCount;
        byte[] body;
        int bodyCount;
        public bool Failed { get; private set; }
        public bool Complete { get { return !Failed && body != null && bodyCount == body.Length; } }
        bool Reject() { Failed = true; return false; }
        // Notification, not a query: EOF while incomplete permanently fails this reader.
        public bool EndOfInput() { return Complete || Reject(); }
        public bool Append(byte[] chunk, int offset, int count) {
            if (Failed) return false;
            if (chunk == null || offset < 0 || count < 0 || offset > chunk.Length - count) return Reject();
            for (int i = offset; i < offset + count; i++) {
                if (headerCount < 4) {
                    header[headerCount++] = chunk[i];
                    if (headerCount == 4) {
                        uint length = (uint)header[0] | ((uint)header[1] << 8)
                            | ((uint)header[2] << 16) | ((uint)header[3] << 24);
                        // Bound before allocation, including unsigned high-bit lengths.
                        if (length == 0 || length > 65536) return Reject();
                        body = new byte[(int)length];
                    }
                } else {
                    if (bodyCount == body.Length) return Reject();
                    body[bodyCount++] = chunk[i];
                }
            }
            return true;
        }
        public byte[] GetBody() {
            if (!Complete) throw new System.InvalidOperationException("frame is not complete and valid");
            return (byte[])body.Clone();
        }
    }
}
