namespace ItemGuard.Execution.Control {
    public enum ControlRole { SPEAKER, SILENT }
    public enum ControlRequestKind { OPERATION, CONTROL_CLOSE }

    // Pure serialized policy. Inputs must already be validated by the transport,
    // schema/identity validator and durable writer; this class proves none of those facts.
    // ObserveEof is ONLY control request-pipe EOF, never stdout/stderr EOF.
    // OPERATION denotes an already allowlisted request, not a wire requestType.
    public sealed class ControlSession {
        enum Stage { Idle, Reading, Ready, Dispatched, Committed, Closing }
        readonly ControlRole role;
        Stage stage;
        bool released;
        ulong requestId;
        bool hasRequestId;
        string requestHash;
        ControlRequestKind requestKind;
        bool closeAckWritten;
        bool eofObserved;
        bool exitObserved;
        public bool Failed { get; private set; }
        public bool CloseAccepted { get; private set; }
        // Revocable control-channel status, NOT output seal or product success. A later
        // protocol fault revokes it; a caller must not cache it as an immutable verdict.
        public bool ClosedCleanly { get { return !Failed && CloseAccepted && closeAckWritten && eofObserved && exitObserved; } }
        public bool ObserveEof() {
            if (Failed) return false;
            if (!CloseAccepted || eofObserved) return Reject();
            eofObserved = true; return true;
        }
        public bool ObserveExit() {
            if (Failed) return false;
            if (exitObserved || (role == ControlRole.SPEAKER && !CloseAccepted)) return Reject();
            exitObserved = true; return true;
        }
        public bool CanAcknowledge { get { return !Failed && stage == Stage.Committed; } }
        public ControlSession(ControlRole role) {
            if (role != ControlRole.SPEAKER && role != ControlRole.SILENT)
                throw new System.ArgumentOutOfRangeException("role");
            this.role = role;
        }
        bool Reject() { Failed = true; return false; }
        // Transport calls this on timeout, broken pipe, invalid frame or ACK write failure.
        public void Fail() { Reject(); }
        public bool BeginFrame() {
            if (Failed) return false;
            if (role != ControlRole.SPEAKER || stage != Stage.Idle) return Reject();
            stage = Stage.Reading; return true;
        }
        public bool CompleteFrame(ulong id, string hash, ControlRequestKind kind) {
            if (Failed) return false;
            if (stage != Stage.Reading || !ValidHash(hash)
                || (kind != ControlRequestKind.OPERATION && kind != ControlRequestKind.CONTROL_CLOSE)
                || (hasRequestId && id <= requestId)) return Reject();
            hasRequestId = true;
            requestId = id; requestHash = hash; requestKind = kind; stage = Stage.Ready; return true;
        }
        public bool TryDispatch(bool hasPendingOperation) {
            // False with Failed=false means buffered until release commit; caller must
            // inspect Failed to distinguish backpressure from terminal rejection.
            if (Failed) return false;
            if (stage != Stage.Ready) return Reject();
            if (!released) return false;
            if (requestKind == ControlRequestKind.CONTROL_CLOSE && hasPendingOperation) return Reject();
            stage = Stage.Dispatched; return true;
        }
        public bool ReleaseCommitted() {
            // One process/session release, not per request (design lines 274-276).
            if (Failed) return false;
            if (released) return Reject();
            released = true; return true;
        }
        public bool CommitRequest(bool firstMirror, bool secondMirror) {
            if (Failed) return false;
            if (stage != Stage.Dispatched || !firstMirror || !secondMirror) return Reject();
            stage = Stage.Committed;
            if (requestKind == ControlRequestKind.CONTROL_CLOSE) CloseAccepted = true;
            return true;
        }
        static bool ValidHash(string value) {
            if (value == null || value.Length != 64) return false;
            foreach (char c in value)
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
            return true;
        }
        public bool AcknowledgeWritten(ulong id, string hash) {
            if (Failed) return false;
            if (!CanAcknowledge || id != requestId || hash != requestHash) return Reject();
            if (CloseAccepted) { closeAckWritten = true; stage = Stage.Closing; }
            else stage = Stage.Idle;
            return true;
        }
    }
}
