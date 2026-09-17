# Successor-35 controlled authorization receipt canonical-byte binding — offline only

Date: 2026-09-13

## Scope

The existing in-memory receipt schema validator checked the exact field set and
expected values, but it accepted only a parsed map. The successor-35 design
requires the external receipt itself to be strict canonical JSON-only.

This slice adds `ValidateCanonicalDocument`. It receives a parsed map plus the
exact bytes obtained by the future fixed-path reader, re-encodes the map using
the guardian's canonical codec, requires byte-for-byte equality, then performs
the existing strict schema and manifest-hash validation.

## TDD evidence

1. Added a desired `ValidateCanonicalDocument` contract and a trailing-space
   rejection case to `ControlledAuthorizationReceiptTests`.
2. The focused test was RED because the method did not exist:
   `CS0117: ControlledAuthorizationReceipt does not contain a definition for
   ValidateCanonicalDocument`.
3. Implemented the minimal canonical-byte equality gate before schema
   validation.
4. GREEN command:

   `bash tools/execution-authority/build/controlled-authorization-receipt-test.sh`

   Output: `CONTROLLED_AUTHORIZATION_RECEIPT checks=10 failures=0`.

5. Aggregate command:

   `bash tools/execution-authority/build/offline-test.sh && git diff --check`

   Exit code: `0`.

## Boundary

This is an offline, in-memory validator only. It does not parse external files,
verify a fixed path, inspect reparse points, create a namespace/journal leaf,
consume an authorization, start Paper, or authorize any runtime action. A
future fixed-path receipt reader must produce both the parsed canonical map and
its original bytes for this gate.
