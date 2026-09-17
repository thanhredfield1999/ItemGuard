# P0 pure dual journal commit — offline evidence

Status: VERIFIED_PURE_OFFLINE_CANDIDATE, NOT_HOST_WRITER / NOT_DURABILITY / NO_RUNTIME_AUTHORIZATION. Initial and bounded followup Opus5 PASS_FOR_OFFLINE_CANDIDATE, parent exact manifest+binary rerun verified. P0 remains OPEN.

## Delivered

- `tools/execution-authority/guardian/DualJournalCommitCore.cs`: serial E.append/full-length + E.flush then C.append/full-length + C.flush. True only after all; failure poisons instance and prohibits ACK/retry. Count/hash remain last successful prefix. False NEVER proves zero bytes reached disk.
- Canonical/hash/order checks before writes; bytes cloned separately; hash precomputed before sink calls. Same-thread callback reentry poisons; competing threads serialize. Positive record bound; no whole journal retained.
- Trusted synchronous injected sinks only; no FileStream/NtCreateFile/job/token/ACL code. Caller owns stable record tree. No runtime consumer. No production assembly shared with recovery.
- Build runner `dual-commit-test.sh` compiles distinct GuardianCommit.dll and RecoveryJournal.dll, then test executable. Added to `offline-test.sh`.

## TDD / coverage

Behavioral RED logs under `docs/reviews/dual-commit-*.log`: red (first commit), failure-red (sink exception), chain-red (bad seq), reentry-red, concurrency-red, null-sink-red. Each followed by actual GREEN. Empty API scaffold first compiled successfully before first behavior RED; no compiler error counted as RED.

Final 60 checks include exact sink order, canonical pair interop with independent recovery, full LF-line hash chaining, short writes/exception each stage, poisoned retry, malformed hash/missing fields/seq/prev, distinct duplicate-vs-record-limit tests, callback reentry, overlapping threads, clone mutation isolation, null sink construction, failure after successful prefix preserving state, same-build independent codec cap equality. Review-added cases are green-first coverage, not new RED claims. Hash reordering is GREEN refactor, not reproduced FIPS/OOM injection.

## Commands / exact artifacts

- `bash tools/execution-authority/build/dual-commit-test.sh`: final-green log, exit0, 60 checks.
- `bash tools/execution-authority/build/offline-test.sh`: `docs/reviews/dual-commit-full-final.log`, exit0. Codec/lifecycle/control/chain/scanner suites all PASS, 50,745 byte mutations and 1,095 truncations included. No aggregated total across overlapping suites.
- `python C:/Users/thanh/AppData/Local/Temp/itemguard-dual-verify.py`: overlays followup manifest onto initial file set, verifies unchanged source hashes; runs exact compiled EXE again exit0/60 checks; output `docs/reviews/dual-commit-final-manifest.json`.
- GuardianCommit.dll SHA256 `a0e144e53fdff645c5136e409fee814cecfcb5c6ae90537eb7644d4cedd95e17`.
- RecoveryJournal.dll SHA256 `a4ecccfcdec31b64d53ea9158357a230d4fa5bb21b9d7c1c54f665a6691e5873`.
- DualJournalCommitTests.exe SHA256 `2d61b24e0fcd8eeefe05876fef460e53222f1648657a9cb66e62a03c9df2bf2f`.
- Binaries under `C:/Users/thanh/AppData/Local/Temp/itemguard-codec-bin/`, not deployed.
- Full final log SHA256 `087338cf809d822886919565b141481423354988ad41cc13f07e40cbdfffdf38`.
- Initial review input `e7df5eef5d51441d9a85cf17d853c0da449992a0ead1cf7c31679fa432afd719`; final followup `c5c5f8917f64c67205a37c50ef1641fde7ed29273ad5b8a2bea35122ab4b2552`. Read actual verdict in both JSONs, not inferred from exit0.
- Disposition `docs/reviews/dual-commit-review-disposition.md`. Followup note: const cap equality catches same-build mismatch, not swapped assembly drift; artifact hashes bind binaries for this test.
- Java artifact unchanged since P1a 364/364 final build; exact JAR verifier repeated successfully, 216 packaged classes match. Java was NOT rebuilt for this C#-only slice.

## Remaining P0 gates

Typed host diagnostics and observed write stages; strict payload schemas/trusted genesis/process binding; real native capability-owned dual writer with safe file+parent identity/ACL/MIC; independent watchdog/native I/O deadline, job close, stdio/pipe drain; fault/quarantine/interruption tests and exact sealed namespace review + fresh user runtime approval. This pure class cannot stand in for any of those gates. No attempt/receipt reuse, no Paper/deploy/restart/production action.

The monolithic harness has bounded synchronization waits; concurrency RED/GREEN is policy evidence, not runtime timing stress. Sinks can lie or block; this core cannot attest physical durability or establish absence. Recovery must inspect both exact journals; no ACK does not erase an established common prefix.
