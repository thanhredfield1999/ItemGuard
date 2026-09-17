# Offline control/session/frame implementation — parent evidence

## Scope and result

Implemented `tools/execution-authority/control/ControlSession.cs` and `ControlFrameReader.cs`, with isolated executable tests and one-command runner `bash tools/execution-authority/build/offline-test.sh`.

This is pure in-memory policy/framing, not a Win32 transport/guardian. It has no pipe/process/ACL/token/runtime-namespace side effects. Boolean durable-commit inputs are test facts supplied by the caller, not real writes. Frame bodies still require schema/role/identity validation. Test composition uses the independently built recovery decoder and shortened test documents, not complete production protocol schemas.

Fresh exact-tree run, commands, complete output and SHA-256 inventory are in `control-implementation-final-verification.json`. All 30 scoped C#/shell files were unchanged between pre/post verification. Results:

- ControlSession: 76 cases, zero failures.
- Frame bounds: 53 cases, zero failures; single-byte fragmented/immutable-body suite also passed.
- In-memory frame -> decoder -> session composition: 9 cases, zero failures, including 64 KiB fragmented input. This does NOT prove actual pipe-buffer/deadline behavior.
- Existing codec suites passed.
- Lifecycle: 4 absent-Paper cases, 128 metadata combinations, 242 prefix/event combinations, 22 four-axis vectors and spawn/branch suites passed.
- `git diff --check`: exit 0.

## Observed TDD sequence

- Request path: compiled stub produced `FAIL ... begin frame`, exit 1; complete read/release gate/dual-commit/ACK implementation then passed.
- Terminal close path: 3 failing cases (acceptance, both close notification orders, pending operation) became green.
- Role/fault/validation guards: 12 behavioral failures (SILENT exit, ACK failure, unknown role/kind, replay/decreasing IDs, invalid hashes) became green. A single patch failed validation without changing files; source was reread and the corrected patch applied.
- Fragmented frame stub: compiled executable failed assertion `one-byte append 0`; implemented reader passed.
- EOF stub: compiled executable failed assertion `EOF with partial body is terminal failure`; implemented sticky EOF rejection passed. Framework unhandled assertion exits were 127, not compilation errors.
- Bounds/state matrix/composition tests added after implementation are supplementary coverage, NOT claimed test-first RED.

## Independent reviews and disposition

Raw envelopes and exact input snapshots are preserved as `control-implementation-{policy,frame,reducer}-opus-raw.json` and corresponding `*-review-input.txt`. All three returned subtype success with `claude-opus-5` in modelUsage (plus ancillary Haiku use).

1. `PASS_FOR_OFFLINE_POLICY`: bounded ControlSession only. Post-review production delta is comments only, no behavior.
   - Revocable ClosedCleanly: CONFIRMED intentional. It is a live control status, not an immutable product verdict; comment documents no caching as authority. Existing post-close-fault test proves revocation.
   - Per-session release: REJECTED as a defect. Design lines 274–276 bind one process release before protocol dispatch; not a fresh process resume per request. Relevant source comment added.
   - SILENT/control EOF naming: CONFIRMED integration risk, not a failing policy path. Source explicitly documents ObserveEof means control-request pipe ONLY, never stdout/stderr. The transport adapter must not route SILENT stdio EOF here.
   - TryDispatch false ambiguity: CONFIRMED API caveat. Documented false+Failed=false means buffering; false+Failed=true means terminal. Tests distinguish both. A transport integration must preserve the distinction.
2. `PASS_FOR_OFFLINE_FRAME_READER`: no behavior changed after review. Added null/zero-length boundary tests and EOF-notification comment. Byte-by-byte copying is bounded, not a measured throughput claim.
3. `PASS_FOR_OFFLINE_REDUCER`: reviewed corrected reducer logic exactly; no behavior changed afterward. Added explicit four-axis vectors to cover reviewer gaps. Reviewer lacked PaperLifecycleTests in bounded input, so its claim that initial/created axes had no coverage was only partially applicable.

## Actual reducer defect corrected

Prior PaperLifecycle required activity for every released process seal. Design lines 776–781 only require activity when factual output/thread/protocol evidence exists. New regression observed two failures (`zero-activity seal/exit retains uncertainty`), then a minimal fix allowed a validated no-activity seal while leaving Code=`NOT_ESTABLISHED` through EXITED. Prefix matrix now includes that branch; nonzero-output-without-activity MUST still be rejected by the not-yet-implemented factual payload validator. No expansion of real-runtime authorization.

## Gate that remains open

P0 is NOT complete. Remaining load-bearing work includes exact request/event/payload schema, guardian-derived process identity, full dual-journal authority and common-prefix validation, real anonymous-pipe/deadline adapter, Win32 token/job/ACL containment and integration, interruption/drift tests and sealed fresh-namespace runtime authorization. No independent review above approves those missing layers.

No Java plugin change, new JAR, Paper invocation, receipt reuse, AV bypass, production deploy/restart, commit or push was performed in this slice. Existing `.hermes/WORKING_STATE.md` remains stale because its earlier protected-file approval timed out; no retry or alternate-path checkpoint edit was attempted. Evidence here is a test/review report, not a substituted operational checkpoint.
