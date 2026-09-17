# Successor-35 design review — 2026-09-11

## Scope and evidence

- Independent Claude Opus 5 read-only review of `docs/design/2026-09-01-natural-break-successor-35-execution-authority.md`.
- No source implementation, executable build, Paper launch, runtime namespace, AV mutation, deploy, release, or production action occurred.
- Reviewer inspected the architecture, containment, role protocol, recovery, artifact DAG, one-shot flow, dual journal, recovery reducer, TDD slices, and gates.

## Findings and parent disposition

1. `BLOCKER` token gate required `IsTokenRestricted` while the design explicitly rejects restricting SIDs. Microsoft documents that the API is nonzero only for a restricting-SID list; `DISABLE_MAX_PRIVILEGE` alone is not that list.
   - `CONFIRMED` from design lines 143–155 and Microsoft Learn `IsTokenRestricted` / `CreateRestrictedToken` documentation.
   - Corrected to require `IsTokenRestricted==0`, a privilege-restricted Low-MIC token, and a regression for that exact shape.

2. `HIGH` `processKey` was used by stdin/output control but had no minting, response schema, or controller binding.
   - `CONFIRMED` from former protocol lines 615–619 and stdio/lifecycle lines 658–680, 791–801.
   - Corrected: only a `ROLE_SPAWN` ACK carries one guardian-minted canonical UUID-v4 key; it is bound to controller role, role, PID, and creation FILETIME. Forged, stale, and cross-role keys are NAK cases.

3. `HIGH` the document simultaneously described a pre-existing journal-leaf collision as consuming the namespace and as a failure before first create that could be retried.
   - `CONFIRMED` from former one-shot and authority-path rules.
   - Corrected: a pre-existing collision blocks the fixed namespace permanently but is not proof that a user authorization was consumed. It cannot be cleaned/retried; only a fresh namespace with fresh explicit approval can be considered.

4. `HIGH` enforcement receipt paths had no sealed base shared by both recovery copies.
   - `CONFIRMED` from former recovery-manifest and authority-path rules.
   - Corrected: the final manifest binds canonical/identity-checked E: and C: receipt roots plus both byte-identical receipt copies; recovery requires all eight exact paths.

5. `MEDIUM` `runToken` was mandatory in records and the Paper stop template but lacked type, generation, and binding rules.
   - `CONFIRMED` from canonical-record and stdio rules.
   - Corrected: guardian generates a CSPRNG canonical lowercase UUID-v4 after both journal leaves exist and before `seq=0`, binds it to namespace/receipt/manifests, and frozen stdio tests must reconstruct the exact bytes.

## Current gate

`DESIGN_REVIEW_PASS_OFFLINE_IMPLEMENTATION_NOT_STARTED`.

## Follow-up review and second correction

A narrow fresh Claude review accepted the no-restricting-SID, collision, and
run-token corrections, then found three further issues: receipt roots had been
added after the receipt pre-image, `processKey` had no rule for guardian-initiated
roles, and the proposed spawn ACK could occur before PID/creation identity existed.

- The DAG now includes fixed receipt roots in the pre-image before receipts are
  minted; only `enforcementReceipts` is deleted by the verifier reconstruction.
- Guardian now mints and journals `processKey` immediately after every successful
  process create; a SPEAKER requester receives it only after
  `PROCESS_ADMISSION_READY` is durable.
- `PROCESS_CREATED_SUSPENDED` records process existence before post-create gates;
  a separate `PROCESS_ADMISSION_READY` gates release. Gate failure follows the
  keyed suspended-termination branch. The next fresh review found stale lifecycle
  tuples and missing RED/crash cases; those text contracts were then corrected.

The final narrow review found no BLOCKER/HIGH in the admission scope. Its two LOW
text gaps (a stale slice-8 order and no explicit RED for ready-before-created) were
aligned in the design immediately afterward. A full fresh adversarial review of the
whole revised design remains required before offline TDD.

## Fresh full review and closure

A fresh full adversarial direct-Claude read-only review of the exact revised design
found one blocker and four high findings. All were `CONFIRMED` against the document
and corrected before a fresh final review:

- added a `journalAuthority` precheck and total, mutually-exclusive summary order;
- made an absent enforcement gate override only the `paperProcess=ABSENT` summary;
- removed the stale/ellipsis lifecycle tuple and retained the complete release order;
- made `ROLE_STDIN_WRITE` a closed operation enum whose bytes/runToken expansion is
  guardian-owned; and
- added the one-shot authorization RED/GREEN slice.

The final review then found one remaining HIGH: mid-run
`JOURNAL_MIRROR_DIVERGED` was ambiguously named as a recovery summary. It is now only
a guardian failure event; recovery keeps `journalAuthority=VALID`, sets
`historyComplete=false`, and applies the existing total summary derivation. A fresh
read-only narrow verification returned `PASS` for that exact closure.

This is a review-pass of the offline specification, not implementation or runtime
evidence. No guardian implementation exists, no TDD slice has been executed, and it
does not authorize `review-bundle-attempt-35`, `attempt-15`, Paper, release, deploy,
or production.
