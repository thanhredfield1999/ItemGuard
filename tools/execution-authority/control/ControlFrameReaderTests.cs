using System;
using ItemGuard.Execution.Control;

class ControlFrameReaderTests {
    static void Check(bool value, string message) { if (!value) throw new Exception(message); }
    static int Main() {
        var reader = new ControlFrameReader();
        byte[] frame = { 2, 0, 0, 0, (byte)'{', (byte)'}' };
        for (int i = 0; i < frame.Length; i++) {
            Check(reader.Append(frame, i, 1), "one-byte append " + i);
            Check(reader.Complete == (i == frame.Length - 1), "no early frame completion");
        }
        byte[] body = reader.GetBody();
        Check(body.Length == 2 && body[0] == '{' && body[1] == '}', "exact body");
        body[0] = 0;
        Check(reader.GetBody()[0] == '{', "caller cannot change stored frame");
        var partial = new ControlFrameReader();
        partial.Append(frame, 0, 5);
        Check(!partial.EndOfInput() && partial.Failed && !partial.Complete, "EOF with partial body is terminal failure");
        Check(!partial.Append(frame, 5, 1), "cannot complete after EOF failure");
        Check(reader.EndOfInput(), "EOF after complete frame");
        Console.WriteLine("PASS one-byte fragmented frame and immutable body");
        return 0;
    }
}
