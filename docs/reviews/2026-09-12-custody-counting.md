# Custody counting — behaviour-based handover tracking (2026-09-12)

## What was asked

Count how often a tracked item genuinely changed hands, not how many events the server recorded.
A player throwing their own item on the floor and picking it back up must not count. Another player
receiving it must count, and the item must remember that the previous player once held it.

## Rules implemented

| Situation | Counted? | Why |
|---|---|---|
| Same player drops and re-picks | No | The holder never changed. No time window involved. |
| Another player picks it up | Yes | Real handover; both players stay in the chain. |
| Taking from a chest | Yes | A player ends up holding it. |
| Putting into a chest | No | Letting go is not someone receiving it. |
| Dying | No | Custody moves when the next player actually picks it up. |
| Hopper / automation | No | Items move between containers with nobody holding them. |
| Same pair swapping repeatedly | Custody moves, count throttled | Stops two colluding accounts farming the number. |

Alongside the handover count the chain exposes **distinct holders**, which two accounts cannot inflate
no matter how long they swap.

## Anti-farm window — a real defect found by running bots

The first implementation reused `anti-dupe.detection-cooldown-ms` to throttle repeated swaps between
the same pair. The bot run returned `LITE_CUSTODY pingpong transfers=3`, i.e. no throttling at all.

Cause: that setting defaults to **5 seconds**, which is shorter than a single deliberate hand-over, so
it could never suppress a swap loop. Unit tests had not caught it because they pass the window
explicitly; only a real timed run exposed the mismatch.

Fix: custody now has its own setting, `tracking.custody-window-ms`, defaulting to **15 minutes**
(`CustodyWindow`). `CustodyWindowTest` asserts the default outlasts a realistic swap loop, so the
regression cannot silently return.

## Design notes

- Derived, not stored. `CustodyChain` replays the history rows the plugin already writes, so there is
  **no schema change** and no migration. It can never claim more than the database recorded, and
  `rowsConsidered()` states the window that was examined.
- `CustodyActionPolicy` is an allowlist (`PICKUP`, `CONTAINER_TAKE`, `SPAWN`). A newly added action
  cannot start affecting custody until it is reviewed and added deliberately.
- `CustodyTransferPolicy` is pure: no clock, no I/O, event time supplied by the caller.
- Presentation follows the privacy fix from earlier today: any player may see the counts for their own
  item; the names of other holders require `itemguard.history.others`. No small caps.

## Runtime evidence — two bots, real events

Fixture `E:/AI.WORK/30_KET_QUA_THU_NGHIEM/itemguard-lite-isolated-2757cc51e17a` (consumed),
candidate `5f34788d87467c74e328d7408cdd4082a9d24d0337bbcc3777210723bd9ed544`, Paper 1.21.11 / Java 21.
Independent verifier returned `PASS_CONTROLLED_SMOKE`.

Read-only SQLite inspection of the fixture database, which is evidence independent of the probe's own
PASS markers:

```
SPAWN                LiteStaff
DROP / PICKUP        LiteStaff   x3      (self drop and re-pick)
DROP LiteStaff  ->   PICKUP LiteMember   (genuine handover)
DROP / PICKUP        alternating x7      (ping-pong between the same pair)
totals: 10 DROP, 10 PICKUP, 1 SPAWN
```

Counted results recorded in the server log:

- `LITE_CUSTODY self transfers=0 holders=1` — three self drop/pickup cycles counted nothing.
- `LITE_CUSTODY transfer transfers=1 holders=2` — the handover counted once and kept both holders.
- `LITE_CUSTODY pingpong transfers=1 holders=2` — seven further swaps added nothing.

Twenty real drop/pickup events produced exactly one counted handover.

New probe cases, all PASS: `custody-self-drop-not-counted`, `custody-handover-counted-once`,
`custody-pingpong-throttled`, `custody-restore-found`, `custody-restore`.

## Harness corrections

Two tooling defects were fixed before the passing run; neither was a product defect:

1. The bot repositioned itself client-side to walk onto drops, which Paper's movement checks reject, so
   the second pickup never happened. Replaced with a server-side `pull` that teleports the player onto
   the drop and waits for the real `PlayerPickupItemEvent`.
2. That `pull` originally scanned for the drop entity once, immediately after the toss, before the
   entity existed. It now polls.

Consumed fixtures from this work: `itemguard-lite-isolated-f3862aab30ac`,
`-7d5ef89c6f88`, `-add3f22f3e6a`, `-2e84fc35006d` (all `FAILED`), and `-2757cc51e17a` (`CAPTURED`).
None were replayed.

## Verification

- Full project: **483/483 tests PASS** (`clean test package`, Java 21).
- Custody unit coverage: `CustodyTransferPolicyTest` 12, `CustodyActionPolicyTest` 6, `CustodyChainTest`
  7, `CustodyPresentationTest` 6, `CustodyWindowTest` 4.
- Runtime tooling contracts: 14/14.

## Boundaries

Custody is shown on the LITE timeline screen only; it has not had client visual acceptance. Chest,
death and hopper rules are covered by unit tests and by the action allowlist, but only the
drop/pickup paths were exercised at runtime in this fixture. The chain describes the retained history
window, not the item's whole life, and the last recorded holder is not proof of present possession.
