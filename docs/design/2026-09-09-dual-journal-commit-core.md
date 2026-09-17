# P0 — bounded dual journal commit core

Scope: implement only the pure sequential commit protocol already required by successor35 design lines516–521, canonical bytes/chain lines523–581 and TDD slice1 line934. Not the host writer or authority. No NtCreateFile/FileStream/process/token/ACL/runtime namespace. The existing approved full guardian architecture is NOT replaced.

Two injected sinks expose Append(byte[]) -> written byte count and Flush(). True return from TryCommit requires exact full canonical line appended and flushed to sink E then sink C; no ACK callback exists here. Caller must not treat old LastCommittedLineSha256 as a new successful commit when TryCommit returns false.

Validate canonical/hash line with guardian codec, sequence starting zero/previous complete-line SHA and positive max-record budget before append. Freeze bytes and provide separate array copy per sink. Any append/flush exception/short write or invalid order poisons instance; no retry/reuse, no further writes. Serialize threads; reject callback reentry terminally. Bound this class's retained state to count/hash, not record history.

Caller owns a stable plain record tree exclusively until TryCommit returns; a hostile custom IDictionary implementation or concurrent mutation during canonical encoding is outside this internal API contract. Sink delegates are trusted synchronous host adapters, not untrusted plugin callbacks. They must not await another thread calling this instance. Serialization here is safety, NOT liveness: blocked native append/flush needs independently owned external watchdog/job-close path. State properties are inspected on the serial owner or after joining the commit operation, not as a cross-thread progress protocol.

External owner retains responsibility for distinct NTFS volume/file/parent identities, final ACL/MIC at relative NtCreateFile creation, holding capability handles, protection and parent flush, native exact write/flush semantics, closing jobs on failure, deadlines and strict event/trusted genesis schema. A mock sink reporting Flush success is NOT disk durability evidence. Host sink cannot fallback to FileStream. Unit PASS must never grant runtime receipt.

TDD cases: canonical positive two commits and recovery verifier interoperability; duplicate/gap/wrong previous, malformed hash, every sink write/flush error/short write, poisoned replay, maximum record bound, reentrancy and concurrent append, immutable byte copies. New pure core remains unused by runtime until host implementation and exact sealed review gates pass.
