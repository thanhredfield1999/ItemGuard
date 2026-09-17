# Successor-35 controlled authorization receipt schema — offline only

Date: 2026-09-13

## Scope

This slice adds the in-memory strict schema validator for the future external, immutable controlled-run authorization receipt. It is intentionally not a receipt file, not a user authorization, and not an invocation mechanism.

## Contract

The validator accepts exactly ten fields:

- `receiptType=CONTROLLED_PAPER_ONE_SHOT`;
- `authorizedControlledNamespace` equal to the guardian's expected namespace;
- exact lowercase SHA-256 values for the bundle, execution-authority, recovery-manifest content, and independent review receipt;
- `userAuthorizationUnixMs` as an unsigned integer;
- explicit `production=false`, `release=false`, and `retry=false` scopes.

An unknown or missing field, wrong type, wrong namespace, stale hash, or any widened scope rejects before journal creation can be considered.

## TDD receipt

The test was written before the validator. The focused build was RED because `ControlledAuthorizationReceipt.cs` did not exist:

```text
error CS2001: Source file 'ControlledAuthorizationReceipt.cs' could not be found
```

The minimal implementation validates only the declared in-memory receipt contents. Final coverage rejects unknown fields, retry/production/release scope widening, wrong namespace, stale review hash, and text in place of the unsigned timestamp.

## Verification

- `bash tools/execution-authority/build/controlled-authorization-receipt-test.sh`
  - `CONTROLLED_AUTHORIZATION_RECEIPT checks=8 failures=0`
- `bash tools/execution-authority/build/offline-test.sh` exited 0.
- `git diff --check` exited 0.

## Limit

No external receipt has been created or read. This slice does not validate canonical bytes, fixed path, reparse/replacement resistance, duplicate JSON keys, review provenance, journal leaf collision/creation order, one-shot consumption, runtime namespace, worker launch, Paper, or natural block-break/publication behavior.
