# Successor-35 authority-genesis recovery slice — offline only

Date: 2026-09-13

## Scope

This slice adds a recovery-side classifier for the fixed early authority prefix:

1. `AUTHORITY_READY` at `seq=0`;
2. `AUTHORIZATION_CONSUMED` at `seq=1` with the same canonical UUID-v4 `runToken`.

It operates only on finite in-memory mirror snapshots. It is not an execution guardian, does not validate the future sealed full journal schema or authorization receipt, does not create a runtime namespace, and does not run Paper.

## Observed RED

A focused scanner test was written first. It required recovery to distinguish empty snapshots, a sole `AUTHORITY_READY` line, an exact two-line consumed prefix, a one-sided consumed suffix, and a mismatched token.

The initial focused compile failed because `AuthorityGenesisScanner` did not exist. The first implementation then failed the one-line genesis assertion because the narrow classifier incorrectly demanded exactly three decoded fields, although a valid decoded journal record also contains chain and hash members. The classifier was narrowed to its declared responsibility: consume already hash-validated common-prefix records and validate the authority fields; full sealed record-schema validation remains a separate gate.

## Green implementation

- `AuthorityGenesis` accepts only the exact sequence/event order for its two-record prefix and requires the same canonical UUID-v4 token.
- `AuthorityGenesisScanner` first uses `CommonJournalScanner` over both finite snapshots. Any non-identical, truncated, invalid, read-failed, or over-budget stream maps to `Invalid`.
- Only an exact common stream ending at EOF is decoded and classified. Empty snapshots are `Empty`; a sole genesis line is `AuthorityReadyOnly`; only the exact chained pair is `AuthorizationConsumed`.

## Verification

- Focused journal suite: `bash tools/execution-authority/build/journal-test.sh`
  - `AUTHORITY_GENESIS checks=10 failures=0`
  - `AUTHORITY_GENESIS_SCANNER checks=5 failures=0`
- Aggregate offline suite: `bash tools/execution-authority/build/offline-test.sh` exited 0.
- `git diff --check` exited 0.

## Limit

`AuthorizationConsumed` here is an offline classification of the two-line common prefix only. It is not authorization to run a worker or Paper, does not validate the future external authorization receipt/manifests/ACL identities, does not prove journal history completeness after this prefix, and is not natural-break, release, or production evidence.
