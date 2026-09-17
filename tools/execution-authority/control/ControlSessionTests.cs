using System;
using ItemGuard.Execution.Control;

class ControlSessionTests {
    static int failures;
    static int cases;
    static readonly string Hash = new string('a', 64);

    static void Check(bool ok, string message) {
        if (!ok) throw new Exception(message);
    }
    static void Run(string name, Action test) {
        cases++;
        try { test(); Console.WriteLine("PASS " + name); }
        catch (Exception e) { failures++; Console.WriteLine("FAIL " + name + ": " + e.Message); }
    }
    static void RequestRoundTrip() {
        var session = new ControlSession(ControlRole.SPEAKER);
        Check(session.BeginFrame(), "begin frame");
        Check(session.CompleteFrame(7, Hash, ControlRequestKind.OPERATION), "complete frame");
        Check(!session.TryDispatch(false), "request must wait for release commit");
        Check(!session.Failed, "fast reader must buffer, not reject before release commit");
        Check(!session.CanAcknowledge, "read complete does not permit ACK");
        Check(session.ReleaseCommitted(), "release commit");
        Check(session.TryDispatch(false), "dispatch after release commit");
        Check(!session.CanAcknowledge, "dispatch does not permit ACK");
        Check(session.CommitRequest(true, true), "dual commit");
        Check(session.CanAcknowledge, "both journals committed");
        Check(session.AcknowledgeWritten(7, Hash), "matching ACK written");
        Check(!session.CanAcknowledge, "ACK cannot be sent twice");
        Check(session.BeginFrame(), "next request only after ACK");
    }
    static ControlSession ReadyClose() {
        var session = new ControlSession(ControlRole.SPEAKER);
        Check(session.ReleaseCommitted(), "release");
        Check(session.BeginFrame(), "begin close");
        Check(session.CompleteFrame(8, Hash, ControlRequestKind.CONTROL_CLOSE), "complete close");
        return session;
    }
    static void CloseRoundTrip(bool exitFirst) {
        var session = ReadyClose();
        Check(session.TryDispatch(false) && session.CommitRequest(true, true), "durable close");
        Check(session.CloseAccepted && !session.ClosedCleanly, "close accepted is not clean close");
        Check(session.AcknowledgeWritten(8, Hash), "close ACK");
        Check(exitFirst ? session.ObserveExit() : session.ObserveEof(), "first notification");
        Check(!session.ClosedCleanly, "one notification is not both EOF and exit");
        Check(exitFirst ? session.ObserveEof() : session.ObserveExit(), "second notification");
        Check(session.ClosedCleanly, "clean control close");
        Check(!session.BeginFrame() && session.Failed, "no requests after close");
        Check(session.CloseAccepted && !session.ClosedCleanly, "failure retains facts, revokes clean status");
    }
    static void ClosePendingOperation() {
        var session = ReadyClose();
        Check(!session.TryDispatch(true) && session.Failed, "pending operation forbids close");
        Check(!session.CloseAccepted && !session.CanAcknowledge, "failed close has no acceptance or ACK");
    }
    static void SilentExit() {
        var session = new ControlSession(ControlRole.SILENT);
        Check(session.ObserveExit() && !session.Failed, "silent exit has no control EOF obligation");
        Check(!session.CloseAccepted && !session.ClosedCleanly, "silent role has no close protocol facts");
        Check(!session.BeginFrame() && session.Failed, "silent role cannot send requests");
    }
    static void AckWriteFailure() {
        var session = ReadyClose();
        Check(session.TryDispatch(false) && session.CommitRequest(true, true), "close commit");
        Check(session.ObserveEof() && session.ObserveExit(), "notifications may precede ACK completion callback");
        Check(!session.ClosedCleanly, "notifications do not prove successful ACK write");
        session.Fail();
        Check(session.Failed && session.CloseAccepted && !session.ClosedCleanly, "ACK failure preserves only durable close fact");
        Check(!session.AcknowledgeWritten(8, Hash), "late ACK cannot revive failed session");
    }
    static void InvalidRole() {
        bool rejected = false;
        try { new ControlSession((ControlRole)42); }
        catch (ArgumentOutOfRangeException) { rejected = true; }
        Check(rejected, "unknown policy must fail at construction");
    }
    static void InvalidKind() {
        var session = new ControlSession(ControlRole.SPEAKER);
        session.BeginFrame();
        Check(!session.CompleteFrame(1, Hash, (ControlRequestKind)42) && session.Failed, "unknown request kind");
    }
    static void Replay(bool lower) {
        var session = new ControlSession(ControlRole.SPEAKER);
        session.ReleaseCommitted(); session.BeginFrame();
        session.CompleteFrame(7, Hash, ControlRequestKind.OPERATION);
        session.TryDispatch(false); session.CommitRequest(true, true); session.AcknowledgeWritten(7, Hash);
        Check(session.BeginFrame(), "next frame");
        Check(!session.CompleteFrame(lower ? 6UL : 7UL, Hash, ControlRequestKind.OPERATION), "replayed/out-of-order ID rejected");
        Check(session.Failed && !session.CanAcknowledge, "replay terminal failure");
    }
    static void InvalidHash(string hash) {
        var session = new ControlSession(ControlRole.SPEAKER);
        session.BeginFrame();
        Check(!session.CompleteFrame(1, hash, ControlRequestKind.OPERATION) && session.Failed, "hash shape rejected");
    }
    static ControlSession AtStage(int stage) {
        var session = new ControlSession(ControlRole.SPEAKER);
        session.ReleaseCommitted();
        if (stage >= 1) session.BeginFrame();
        if (stage >= 2) session.CompleteFrame(8, Hash, ControlRequestKind.CONTROL_CLOSE);
        if (stage >= 3) session.TryDispatch(false);
        if (stage >= 4) session.CommitRequest(true, true);
        if (stage >= 5) session.AcknowledgeWritten(8, Hash);
        if (stage >= 6) session.ObserveEof();
        if (stage >= 7) session.ObserveExit();
        Check(!session.Failed, "valid prefix");
        return session;
    }
    static void CannotRevive(ControlSession session) {
        Check(session.Failed && !session.CanAcknowledge && !session.ClosedCleanly, "failed terminal");
        Check(!session.BeginFrame() && !session.CompleteFrame(9, Hash, ControlRequestKind.OPERATION)
            && !session.TryDispatch(false) && !session.CommitRequest(true, true)
            && !session.AcknowledgeWritten(8, Hash) && !session.ObserveEof()
            && !session.ObserveExit() && !session.ReleaseCommitted(), "no state-changing action can revive");
    }
    static void FaultAtStage(int stage) {
        var session = AtStage(stage);
        bool accepted = session.CloseAccepted;
        session.Fail();
        CannotRevive(session);
        Check(session.CloseAccepted == accepted, "failure must not erase durable fact");
    }
    static void InvalidAction(int stage, string action) {
        var session = AtStage(stage);
        bool result;
        switch (action) {
            case "begin": result = session.BeginFrame(); break;
            case "complete": result = session.CompleteFrame(9, Hash, ControlRequestKind.OPERATION); break;
            case "dispatch": result = session.TryDispatch(false); break;
            case "commit": result = session.CommitRequest(true, true); break;
            case "ack": result = session.AcknowledgeWritten(8, Hash); break;
            case "eof": result = session.ObserveEof(); break;
            case "exit": result = session.ObserveExit(); break;
            default: throw new Exception("bad test action");
        }
        Check(!result, action + " must reject at " + stage);
        CannotRevive(session);
    }
    static void SingleMirrorFailure(int mask) {
        var session = AtStage(3);
        Check(!session.CommitRequest((mask & 1) != 0, (mask & 2) != 0), "no one-sided ACK");
        Check(!session.CloseAccepted, "close acceptance requires both commits");
        CannotRevive(session);
    }
    static void WrongAck(bool id) {
        var session = AtStage(4);
        Check(!session.AcknowledgeWritten(id ? 9UL : 8UL, id ? Hash : new string('b', 64)), "bind exact ACK");
        CannotRevive(session);
    }
    static void LateAckCallback() {
        var session = AtStage(4);
        Check(session.ObserveExit() && session.ObserveEof() && !session.ClosedCleanly, "callbacks do not imply ACK");
        Check(session.AcknowledgeWritten(8, Hash) && session.ClosedCleanly, "successful ACK completion joins notifications");
    }
    static int Main() {
        Run("request round trip and release gate", RequestRoundTrip);
        Run("close EOF before exit", delegate { CloseRoundTrip(false); });
        Run("close exit before EOF", delegate { CloseRoundTrip(true); });
        Run("pending operation blocks close", ClosePendingOperation);
        Run("silent exit without protocol", SilentExit);
        Run("ACK write failure after notifications", AckWriteFailure);
        Run("unknown role", InvalidRole);
        Run("unknown request kind", InvalidKind);
        Run("duplicate request ID", delegate { Replay(false); });
        Run("decreasing request ID", delegate { Replay(true); });
        foreach (var hash in new [] { null, "", new string('A', 64), new string('g', 64), new string('a', 63), new string('a', 65) }) {
            string value = hash;
            Run("invalid hash " + (value == null ? "null" : value.Length.ToString()), delegate { InvalidHash(value); });
        }
        Run("ACK callback after EOF/exit", LateAckCallback);
        Run("wrong ACK ID", delegate { WrongAck(true); });
        Run("wrong ACK hash", delegate { WrongAck(false); });
        for (int mask = 0; mask < 3; mask++) {
            int m = mask;
            Run("mirror failure " + m, delegate { SingleMirrorFailure(m); });
        }
        for (int stage = 0; stage <= 7; stage++) {
            int s = stage;
            Run("fault terminal at stage " + s, delegate { FaultAtStage(s); });
            foreach (string action in new [] { "begin", "complete", "dispatch", "commit", "ack", "eof", "exit" }) {
                bool allowed = (action == "begin" && s == 0) || (action == "complete" && s == 1)
                    || (action == "dispatch" && s == 2) || (action == "commit" && s == 3)
                    || (action == "ack" && s == 4) || (action == "eof" && (s == 4 || s == 5))
                    || (action == "exit" && s >= 4 && s <= 6);
                if (allowed) continue;
                string a = action;
                Run("reject " + a + " at stage " + s, delegate { InvalidAction(s, a); });
            }
        }
        Console.WriteLine("CONTROL cases=" + cases + " failures=" + failures);
        return failures == 0 ? 0 : 1;
    }
}
