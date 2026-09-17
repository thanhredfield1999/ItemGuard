using System;
using ItemGuard.Execution.Control;

class ControlFrameBoundsTests {
    static int cases;
    static int failures;
    static void Check(bool value, string message) { if (!value) throw new Exception(message); }
    static void Run(string name, Action test) {
        cases++;
        try { test(); }
        catch (Exception e) { failures++; Console.WriteLine("FAIL " + name + ": " + e.Message); }
    }
    static byte[] Frame(int size) {
        var frame = new byte[size + 4];
        frame[0] = (byte)size; frame[1] = (byte)(size >> 8);
        frame[2] = (byte)(size >> 16); frame[3] = (byte)(size >> 24);
        for (int i = 4; i < frame.Length; i++) frame[i] = (byte)i;
        return frame;
    }
    static void NoBody(ControlFrameReader reader) {
        bool rejected = false;
        try { reader.GetBody(); } catch (InvalidOperationException) { rejected = true; }
        Check(rejected, "partial or failed body must not escape");
    }
    static void Fragment(int size, int piece) {
        var frame = Frame(size);
        var reader = new ControlFrameReader();
        for (int offset = 0; offset < frame.Length; offset += piece) {
            int count = Math.Min(piece, frame.Length - offset);
            Check(reader.Append(frame, offset, count), "fragment accepted");
            Check(reader.Complete == (offset + count == frame.Length), "exact completion");
        }
        var body = reader.GetBody();
        for (int i = 0; i < body.Length; i++) Check(body[i] == frame[i + 4], "all bytes exact");
        Check(reader.EndOfInput(), "EOF after full frame");
    }
    static void BadLength(uint length) {
        var reader = new ControlFrameReader();
        byte[] header = { (byte)length, (byte)(length >> 8), (byte)(length >> 16), (byte)(length >> 24) };
        Check(!reader.Append(header, 0, 4) && reader.Failed && !reader.Complete, "length rejected before allocation");
        NoBody(reader);
        Check(!reader.Append(Frame(1), 0, 5), "cannot reuse failed reader");
    }
    static void Truncated(int prefix) {
        var reader = new ControlFrameReader();
        var frame = Frame(10);
        Check(reader.Append(frame, 0, prefix) && !reader.Complete, "truncated frame waits");
        NoBody(reader);
        Check(!reader.EndOfInput() && reader.Failed, "EOF rejects prefix");
        Check(!reader.Append(frame, prefix, frame.Length - prefix), "late suffix cannot repair");
    }
    static void Trailing(bool sameChunk) {
        var reader = new ControlFrameReader();
        var frame = Frame(3);
        if (sameChunk) {
            Array.Resize(ref frame, frame.Length + 1);
            Check(!reader.Append(frame, 0, frame.Length), "trailing byte in same read");
        } else {
            Check(reader.Append(frame, 0, frame.Length), "valid frame");
            Check(!reader.Append(new byte[] { 0 }, 0, 1), "later trailing byte");
        }
        Check(reader.Failed && !reader.Complete, "no complete body after trailing data");
        NoBody(reader);
    }
    static int Main() {
        foreach (int size in new [] { 1, 2, 255, 256, 65535, 65536 })
            foreach (int piece in new [] { 1, 3, 17, 4096, 70000 }) {
                int s = size, p = piece;
                Run("fragment size=" + s + " piece=" + p, delegate { Fragment(s, p); });
            }
        foreach (uint length in new uint[] { 0, 65537, 0x80000000, uint.MaxValue }) {
            uint n = length;
            Run("length=" + n, delegate { BadLength(n); });
        }
        for (int prefix = 0; prefix < 14; prefix++) {
            int p = prefix;
            Run("truncated prefix=" + p, delegate { Truncated(p); });
        }
        Run("same-read trailing byte", delegate { Trailing(true); });
        Run("later trailing byte", delegate { Trailing(false); });
        Run("null chunk rejected", delegate {
            var reader = new ControlFrameReader();
            Check(!reader.Append(null, 0, 0) && reader.Failed, "null is not empty input");
        });
        Run("zero-byte reads preserve state", delegate {
            var reader = new ControlFrameReader();
            Check(reader.Append(new byte[0], 0, 0) && !reader.Complete, "empty read before frame");
            var frame = Frame(1);
            Check(reader.Append(frame, 0, frame.Length) && reader.Complete, "complete");
            byte before = reader.GetBody()[0];
            Check(reader.Append(frame, frame.Length, 0) && reader.Complete, "empty read after frame");
            Check(reader.GetBody()[0] == before, "empty read unchanged");
        });
        Run("invalid buffer ranges", delegate {
            var reader = new ControlFrameReader();
            Check(!reader.Append(new byte[4], int.MaxValue, 1), "range overflow reject");
            Check(reader.Failed, "sticky range error");
        });
        Console.WriteLine("FRAME_BOUNDS cases=" + cases + " failures=" + failures);
        return failures == 0 ? 0 : 1;
    }
}
