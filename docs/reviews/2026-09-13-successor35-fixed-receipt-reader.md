# Successor-35 fixed receipt read boundary — offline only

Date: 2026-09-13

## Scope

This slice closes the gap between an in-memory authorization map and the future
fixed-path external receipt input.

- `GuardianCanonicalDecoder` parses receipt bytes locally to the guardian and
  accepts only a byte-for-byte guardian canonical-JSON round trip.
- `GuardianFixedReceiptReader` opens the specified leaf with
  `NativeJournalLeaf.OpenExistingReadOnly`, verifies the held native
  FILE_ID_INFO identity against the pre-captured identity, reads through that
  same handle with an explicit byte cap, then runs canonical-byte and strict
  authorization schema validation.
- `NativeJournalLeaf.ReadAllExact` reads through its held native handle; it
  does not reopen the pathname after the identity check.

## TDD evidence

1. Native handle read coverage was first added to
   `NativeJournalIdentityTests`; RED compile failure showed `ReadAllExact` was
   absent. The minimal held-handle read implementation made it GREEN.
2. Guardian canonical parser test was first added; RED compile failure showed
   `GuardianCanonicalDecoder` was absent. GREEN rejects unsorted members,
   whitespace and duplicate names.
3. Fixed-reader test was first added; RED compile failure showed
   `GuardianFixedReceiptReader` was absent. GREEN confirms the exact captured
   leaf binds successfully, a different leaf cannot substitute for it, and the
   final leaf DACL denies same-user replacement.

Focused results:

- `NATIVE_JOURNAL_IDENTITY checks=9 failures=0`
- `GUARDIAN_CANONICAL_DECODER checks=4 failures=0`
- `CONTROLLED_AUTHORIZATION_RECEIPT checks=11 failures=0`
- `GUARDIAN_FIXED_RECEIPT_READER checks=3 failures=0`

## Boundary

All tests use disposable scratch roots and leave no runtime namespace, external
receipt, journal consumption, worker, Paper server, ItemGuard product mutation,
or release action. The actual fixed receipt path, pre-capture/manifest sealing,
namespace admission order and runtime guardian are still unimplemented.
