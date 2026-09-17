# Successor-35 dual genesis run-token slice — offline only

Date: 2026-09-13

## Scope

This slice strengthens the host-only `NativeDualJournalAdmission` seam. It is not an `ExecutionGuardian`, does not validate a controlled authorization receipt, does not create a runtime namespace or worker process, and does not run Paper.

## Observed RED

`NativeAuthorityReadyTests` was extended first to require that admission exposes a canonical UUID-v4 `runToken`, binds it into the dual-durable `AUTHORITY_READY` record, rejects `AUTHORIZATION_CONSUMED` before genesis, and accepts it only as sequence 1 after durable genesis.

Before implementation, compiling the focused test failed because `NativeJournalAdmissionReceipt` had no `RunToken`; after the first implementation increment, the focused test failed because `NativeDualJournalAdmission` had no `TryCommitAuthorizationConsumed` method. These failures established the absent behavior rather than a runtime result.

## Green implementation

`NativeDualJournalAdmission.Create` now mints a canonical UUID-v4 token only after both native journal leaves have been created. The immutable admission receipt carries that token. The token is bound into `AUTHORITY_READY` at sequence 0. `TryCommitAuthorizationConsumed` only permits sequence 1 after a successful genesis commit and binds the same token.

The existing serial `DualJournalCommitCore` writes the exact canonical line to both held leaves and requires both append/flush operations before incrementing its committed count. A rejected pre-genesis consumption attempt leaves the count at zero.

## Verification

- Focused: `bash tools/execution-authority/build/native-authority-ready-test.sh`
  - `NATIVE_AUTHORITY_READY checks=15 failures=0`
- Aggregate offline suite: `bash tools/execution-authority/build/offline-test.sh`
  - exit 0; includes codec, lifecycle, control, scanner/chain, dual-commit, native journal, native volume, dual admission, authority-ready, job/token, recovery-manifest and role-schema suites.
- `git diff --check` exit 0.

## Limit

This is `VERIFIED` only as disposable offline host-local evidence. It does not prove recovery classification, sealed artifacts, review receipt validation, a one-shot runtime authorization, AV resilience, Java/Node worker containment, natural block-break identity/publication, Paper runtime, release, or production behavior.
