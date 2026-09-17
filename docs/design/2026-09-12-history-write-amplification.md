# Threat model — history write amplification (2026-09-12)

Written before fixing, because the previous two attempts patched what was displayed instead of what
was recorded, and the problem came back both times.

## Observed

From the live manual session `itemguard-lite-manual-8f262cd94284`, one player, two items, no other
participant:

```
1  11GXH7  PICKUP  ThanhRedfield
2  4DQ6XW  PICKUP  ThanhRedfield
3  11GXH7  DROP    ThanhRedfield
4  11GXH7  PICKUP  ThanhRedfield
5  4DQ6XW  DROP    ThanhRedfield
6  4DQ6XW  PICKUP  ThanhRedfield
7  4DQ6XW  DROP    ThanhRedfield
8  4DQ6XW  PICKUP  ThanhRedfield
9  11GXH7  DROP    ThanhRedfield
10 11GXH7  PICKUP  ThanhRedfield
```

`item_uuid` never changes. The holder never changes. Yet every throw-and-retake writes two permanent
rows. `ItemTrackingService.logHistory` records unconditionally, so the write rate is bounded only by
how fast a player can press Q.

## What a hostile player does with this

**T1 — Storage exhaustion.** Holding Q on a stack of tracked items writes rows continuously. One
player can add tens of thousands of rows per hour. The database is SQLite on the server disk, so this
is an availability problem for every other feature that reads history, and eventually for the host.

**T2 — Evidence flooding.** History is the investigation tool. A duper who wants to hide a real
transfer can bury it: perform the illegitimate move, then spam thousands of self drop/pickup rows on
the same item. The real event scrolls out of every bounded query window (`LIMIT 45`, and the GUI page
of 28). The plugin's own retention窗口 becomes the attacker's shredder.

**T3 — Fabricated reputation.** Any figure derived from raw event counts is attacker-controlled.
Collapsing this at render time is not enough: any future feature, export, or admin query that reads
the rows sees the inflated data.

**T4 — Tick cost.** Each event is a database write on a gameplay path. Sustained spam by several
players turns an audit feature into a performance regression.

**T5 — Cross-player griefing.** Rows are written per acting player, but they attach to the *item*.
A player who briefly obtains someone else's tracked item can spam it, degrading the owner's history
without ever stealing the item.

## Why display-level fixes failed

Two fixes were attempted at the presentation layer. Both were correct about what a player should see,
and both left the storage, flooding and performance problems untouched, because the rows were still
being written. The defect is at the write boundary, so the fix belongs there.

## Decision

Suppress redundant writes at the source: when the same player repeats the same action on the same
item identity within a short window, and nothing about custody changed, update the existing row's
timestamp instead of appending a new one. Keep one row for the fact, plus a repeat counter so the
behaviour remains visible and auditable.

Non-negotiables for the fix:

- A genuine handover, a different action, or a different item identity must always write.
- Suppression must never lose the fact that the event happened, only the duplication of it.
- No schema-breaking change; additive only.
- The suppression window must be its own setting, not borrowed from an unrelated feature. The
  previous attempt reused the five-second duplicate-detection cooldown and it was useless.
