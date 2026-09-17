# Successor-35 native one-shot namespace admission — offline only

Date: 2026-09-13

## Scope

`NativeOneShotNamespaceAdmission` joins the existing native dual-journal
admission with a fail-closed namespace preflight.

- Any existing E: journal leaf, C: journal leaf, or future runtime directory
  makes the namespace unavailable.
- A fresh namespace creates native journal leaves through the existing
  `FILE_CREATE` path. A race at creation cannot overwrite an existing leaf;
  `NativeDualJournalWriter.Create` fails and leaves the partial artifact
  observable.
- The wrapper does not create the runtime directory. That remains prohibited
  until both `AUTHORITY_READY` and `AUTHORIZATION_CONSUMED` have committed.

## TDD evidence

1. Added `NamespaceOneShotPolicyTests`; it was RED because the policy did not
   exist.
2. Implemented the pure collision predicate; GREEN:
   `NAMESPACE_ONE_SHOT_POLICY checks=4 failures=0`.
3. Added `NativeOneShotNamespaceAdmissionTests`; it was RED because the native
   wrapper did not exist.
4. Implemented the wrapper; GREEN:
   `NATIVE_ONE_SHOT_NAMESPACE checks=3 failures=0`.

The native test creates both journal leaves, commits the two genesis records,
then proves a second invocation is blocked by the retained leaf. Scratch roots
are removed in `finally`.

## Boundary

This remains host-only test tooling. It has no sealed fixed receipt path or
review receipt, no runtime directory creation, no process launch, no Paper,
and no ItemGuard product/release/deploy action.
