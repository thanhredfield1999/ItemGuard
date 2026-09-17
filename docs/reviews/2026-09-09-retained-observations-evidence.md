# Retained observations browser — final offline evidence (2026-09-09)

## Verdict

VERIFIED OFFLINE CANDIDATE only. C3 slice now includes exact-source independent reviews, fresh full build and byte-matched candidate. Whole C3/HavenBags/custody/GUI runtime/visual/release remain OPEN. No Paper/deploy/restart/commit/push, no reused runtime receipt, no schema/writer/scan/adapter changes.

## What is delivered

Profile → Nơi đã quan sát → retained observation list → detail → back, preserving catalog context. Exact code + UUID query, 100 rows + truncation indicator, 36/page. Time/physical key/slot/epoch completion explicitly bounded; unknown slot rendered unknown. Profile aggregate record labeled separately from observation source. Same-epoch and cross-epoch rows are NOT simultaneous-copy evidence; no inferred holder/transfer/ownership/absence. Empty, pruned, partial and unsupported external coverage stay explicit.

## Fresh verification

- Command: `python C:/Users/thanh/AppData/Local/Temp/itemguard-catalog-maven.py clean test package` (Java21 wrapper for `mvnw.cmd clean test package --no-transfer-progress`). `obs-full-final.log` BUILD SUCCESS, exit0.
- XML aggregate: 378 tests, 0 failures/errors/skipped; 52 catalog tests. Focused final `obs-final-label-green.log`: 22 PASS.
- `itemguard-obs-verify.py snapshot` pinned 273 source/test/resource/pom inputs immediately before full build. Verification after build confirms unchanged inputs and merged R2/followup/final reviewed file hashes. `obs-build-inputs.json`, `obs-final-manifest.json`.
- `target/ItemGuard-1.0.0.jar` SHA256 `c8f95756adedb4a52c09908df0d43f90b7d64d9015ce081ff3b93501d7f6a534`.
- Exact 218 com/itemguard class entries equal target/classes byte-for-byte; resources matched; no duplicate ZIP entries. SQLite JDBC/Windows native/service provider present; Mockito/ByteBuddy/Objenesis not packaged.
- `git diff --check` exit0; `codegraph sync E:/AI.WORK/ItemGuard` exit0.
- P0 regression during this slice: `bash tools/execution-authority/build/offline-test.sh` exit0 (`obs-p0-regression.log`). No C# source changed by this slice. Does not imply real durability/native execution.

## Behavioral evidence and coverage

- Original query/scalar/navigation RED logs retained; scalar assertion `holder key preview must be bounded`, navigation `recorded locations must be navigable`.
- Review label REDs: `obs-profile-label-red.log`, `obs-cross-epoch-red.log`, `obs-unknown-slot-red.log`, `obs-same-epoch-red.log`; final targeted suite GREEN. No compilation/seed/runner error counted as behavioral RED.
- Repository actual SQLite: identity isolation/order/cap/truncation/scalar length, interrupted query then populated recovery, pruning not absence, 100000 unrelated rows + real query plan (identity index/no temp sort) and reopen.
- NULL constraint coverage uses slot7 witness after failed update, no ambiguous JDBC NULL→0 pass.
- `CatalogObservationFlowTest`: actual SQLite + CatalogReadGate + controller with deterministic clock/UI seam. Busy retry, profile/history context, requery after pruning. It started GREEN after seed repair; coverage only, not retrospective TDD claim.
- Controller permissions, stale actions, close/revoke, all 100 cached rows across pages, error/retry exact identity, no prior item leak; CatalogUi Host/proxy lifecycle tests are OFFLINE, not Paper/client acceptance.

## Independent review chain

- `obs-review.json`: INVALID first response, no verdict. Not counted despite process exit0.
- R2 `obs-r2-review.json`: Opus5 PASS_FOR_OFFLINE_CANDIDATE. Input hash `72699a9909a3c5a1912545ef55290e96ddca7b1b6cce27a164bf426d2a139076`.
- Followup `obs-followup-review.json`: PASS_FOR_OFFLINE_CANDIDATE. Input hash `8617b29ac36fb55bf42bb08a0db5376d33e38cffbee78a1a545015c3b6acc2ab`.
- Last label/test followup `obs-final-review.json`: PASS_FOR_OFFLINE_CANDIDATE, blocking_issues empty. Input hash `03c2530062a9e342ff98bfd870df9e4aef7b040d2b1fc058b1748ff80764b8c0`.
- Parent validated all three input hashes, dispositions, actual test output and current source/JAR. Dispositions `obs-review-disposition.md`, `obs-final-disposition.md`. Static reviewers did not execute tests; their reviews are not runtime authorization.

## Open boundaries

- Retained observation cache deletes earlier epochs; not durable movement history. Completed bounded scan is not full-system completeness; same-epoch observations are not atomic snapshot.
- Raw epoch token (max(clock,previous+1)) intentionally not fabricated as reliable wall time; compact grouping labels and long lore/client rendering remain C4. Shared one-inflight/1s gate and generic busy/error text may hinder rapid/multi-admin navigation; no weakened budget.
- Hypothetical unsupported older nullable schema not migrated; current schema NULL rejection tested. Data previews are bounded but may be long on client.
- C1 rare/no-match general contains search still budget-limited. Observation exact-index scale probe does not close that gate.
- HavenBags installed JAR/version/hash/server path unanswered. No external adapter or current-holder claim; C2 provenance, durable C3, P0 native and P2–P6 remain open.
- `.hermes/WORKING_STATE.md` retains earlier checkpoint after prior approval timeout; not retried or bypassed.
