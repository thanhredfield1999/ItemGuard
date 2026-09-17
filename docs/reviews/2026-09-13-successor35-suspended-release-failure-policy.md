# Successor-35 suspended-release return contract — offline only

Date: 2026-09-13

## Scope

This bounded host-only slice tightens `NativeSuspendedWorker.Release` around the Win32 `ResumeThread` return contract. The existing primitive creates a child with `CREATE_SUSPENDED`, assigns its Job, then verifies `IsProcessInJob` before any release attempt.

It is not an `ExecutionGuardian`, does not execute the privileged child-launch path on this host, and does not create a runtime namespace, run Paper, or mutate ItemGuard product code.

## Observed gap

The earlier implementation treated every non-`uint.MaxValue` `ResumeThread` result as a valid release. That is weaker than the design: a valid release requires the exact previous suspend count `1` from the single `CREATE_SUSPENDED` state. A return of `0` means the process was already runnable; a count greater than `1` leaves it suspended; `uint.MaxValue` is an API failure.

For any non-exact result, leaving the owned process alive while throwing would retain an uncertain execution state.

## TDD receipt

Two pure, no-launch tests were written first:

1. only prior suspend count `1` is a valid release;
2. every other return result requires termination before failure.

Both initially failed to compile because the predicates did not exist. The minimum implementation added `IsExactFirstResumeResult` and `MustTerminateAfterResumeResult`.

`Release` now terminates through its held process handle and waits for a signaled process handle before surfacing a non-exact `ResumeThread` failure.

## Verification

- `bash tools/execution-authority/build/native-suspended-worker-release-test.sh`
  - `NATIVE_SUSPENDED_WORKER_RELEASE checks=4 failures=0`
- `bash tools/execution-authority/build/native-suspended-worker-failure-policy-test.sh`
  - `NATIVE_SUSPENDED_WORKER_FAILURE_POLICY checks=4 failures=0`
- `bash tools/execution-authority/build/offline-test.sh` exited 0.
- `git diff --check` exited 0.

## Limit

The host lacks `SeIncreaseQuotaPrivilege`; the existing privilege-dependent `CreateProcessAsUserW` child launch test remains excluded from `offline-test.sh` and was not retried. These pure contract tests do not prove the real API return branch, successful termination, process DACL, token compatibility, journal intents/observations, or any natural-break/publication outcome.
