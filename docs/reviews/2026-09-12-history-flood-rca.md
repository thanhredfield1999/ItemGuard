# Root cause: history write amplification (2026-09-12)

## The report

"vẫn bị" — the visible count still climbed when the owner threw an item out and took it back. Two
prior fixes had been applied and both failed.

## Why the first two fixes failed

Both were made at the presentation layer: the custody count was already correct, so the numbers were
recomputed and collapsed at render time. That addressed the symptom a player reads and left the cause
untouched, so the defect returned each time.

Concretely: the log was never read before fixing. Once read, it was obvious the problem was upstream
of any display code.

## Evidence that located the cause

Live session `itemguard-lite-manual-8f262cd94284`, one player, two items, no other participant:

```
1  11GXH7  PICKUP  ThanhRedfield
3  11GXH7  DROP    ThanhRedfield
4  11GXH7  PICKUP  ThanhRedfield
9  11GXH7  DROP    ThanhRedfield
10 11GXH7  PICKUP  ThanhRedfield
```

`item_uuid` constant, holder constant, yet every throw-and-retake wrote two permanent rows.
`ItemTrackingService.logHistory` recorded unconditionally: the write rate was bounded only by how
fast a player can press Q.

## Threat model, written before the fix

Full version: `docs/design/2026-09-12-history-write-amplification.md`.

- **T1 storage exhaustion** — tens of thousands of rows per hour from one player; SQLite on the
  server disk, so it degrades every history read and eventually the host.
- **T2 evidence flooding** — the dangerous one. A duper performs the real transfer, then spams self
  drop/pickup on the same item until the incriminating row falls out of every bounded query window
  (`LIMIT 45`, GUI page of 28). The plugin's retention window becomes the attacker's shredder.
- **T3 fabricated reputation** — any figure derived from raw counts is attacker-controlled, including
  future exports and admin queries, not just the current GUI.
- **T4 tick cost** — a database write per keypress on a gameplay path.
- **T5 cross-player griefing** — rows attach to the item, so briefly holding someone else's tracked
  item is enough to pollute its history without stealing it.

## The fix

`com.itemguard.history.HistoryWriteGate` at the write boundary. A row is folded only when item
identity, actor and action are all unchanged and the repeat falls inside a 60 s window. Folding
updates the existing row's timestamp and raises a `repeats=` counter in the existing
`additional_data` column, so the behaviour stays auditable and no schema change is needed.

Always writes, never suppressed:

- a different player acting — the handover is the entire point of the audit trail
- a different action
- a different item identity
- the same action after the window elapses
- custody returning to an earlier holder (remembered actions are cleared whenever the holder changes)
- any row with a missing identity, actor or action

Memory is bounded by an LRU cap of 4096 identities, so spamming distinct items cannot turn the
protection into a leak.

### Design error caught by the tests

The first implementation compared each event against the immediately preceding one. A throw-and-retake
loop alternates DROP and PICKUP, so every event differs from its predecessor and nothing would ever
have been suppressed. The tests failed with `expected: <COALESCE> but was: <WRITE>`, and recency is
now tracked per action rather than per item.

## Verification

Unit: `HistoryWriteGateTest` 11/11, including a 500-event spam loop asserting exactly 2 rows.
Full suite 498/498 PASS.

Runtime, fixture `itemguard-lite-isolated-ab6fe2eb071b`, JAR
`dd8696556452b1a98f3d9b89a24b12f7470a866712ee5858cedecf5ec5df6369`, all 15 cases PASS including the
new `history-flood-bounded`. Raw database after the run:

```
2  VPDGJK  INVENTORY_MOVE  LiteStaff   repeats=4
3  VPDGJK  DROP            LiteStaff   repeats=4
4  VPDGJK  PICKUP          LiteStaff   repeats=3
5  VPDGJK  PICKUP          LiteMember  (null)
```

Eleven self drop/pickup events from one holder occupy three rows. The handover to LiteMember writes
its own row, unsuppressed.

## Process correction

The storage assertion (`liteprobe historyrows`) now runs inside the standard smoke sequence, so a
regression of this class fails the fixture instead of reaching a human. A rendered figure is no
longer accepted as evidence for a storage claim.

Also fixed while verifying: the probe read a non-existent config key (`probe.code` instead of
`expected-code`), which produced a FAIL with `code=` empty. That was a harness defect, not a
product defect, and it is why the first flood run failed.

## Not covered

Container, death and hopper paths have unit coverage and an allowlist, but no runtime evidence. The
runtime evidence here covers drop and pickup only.
