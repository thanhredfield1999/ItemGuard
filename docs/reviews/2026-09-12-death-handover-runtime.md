# Death hand-over — runtime evidence, and a false loss row it exposed (2026-09-12)

Two real clients on a real Paper server: `IGVictim` dies carrying a tracked sword, `IGLooter` picks
it up off the ground. Driven by `tools/lite-runtime/death-probe.cjs`.

## Result

Raw database for item `U5LHZ8`:

```
SPAWN   IGVictim
DEATH   IGVictim
PICKUP  IGLooter
```

Custody, computed by the same rule the plugin uses (only `PICKUP`, `CONTAINER_TAKE` and `SPAWN`
claim custody):

```
handovers : 1
holders   : IGVictim, IGLooter
```

Correct. Dying is a release, not a claim, so the victim's death does not count; the looter taking it
off the ground is the single genuine change of hands.

## The bug this found

The first run produced this instead, for item `WRC4QI`:

```
SPAWN    IGVictim
DEATH    IGVictim
CLEARED  IGVictim     <-- false
PICKUP   IGLooter
```

A `CLEARED` row for loot that was lying on the ground, about to be picked up.

**Why it is serious.** `CLEARED` confirms destruction, and a destroyed identity is restorable. A
false loss row on an item that still exists is a duplication path: an admin reading the history would
see "removed by a command" and could legitimately hand out a replacement while the original sat in
the looter's inventory.

**Root cause.** `ItemTrackingService.onItemDeath` wrote a `DEATH` history row but never updated
`tracked_items.last_action`, so it stayed at `PICKUP`. The inventory watcher then asked that column,
saw a holding action, judged the sword's disappearance unexplained, and recorded a removal.

**Two fixes, because the summary column was only the proximate cause:**

1. `onItemDeath` now updates the stored last action alongside the history row.
2. `getLastRecordedAction` reads the newest history row instead of `tracked_items.last_action`.
   History is append-only so it cannot drift; the summary column is only as good as every writer
   remembering to maintain it, and one forgot. It remains as a fallback when no history exists.

Regression test: `DeathIsNotAClearRegressionTest`. Full suite 633/633 PASS.

## Harness defects found and fixed

1. `ops.json` is read only at startup, so bots joining later were not operators and every command
   returned nothing. The probe now grants op through the `console.in` channel once both are online.
2. Teleporting a bot on top of a dropped item does not collect it; the server only picks items up
   while the player moves through them. The looter now walks into the drop.
3. `entity.objectType` is deprecated in this prismarine build and printed a warning per call;
   replaced with `entity.name`.

## Limitation

The client-side `playerCollect` event never fired, so `collected` reads false in the probe output
even though the server recorded the pickup. The database is the authority here, and it shows the
`PICKUP` row attributed to `IGLooter`. The custody figures above are computed from those rows, not
from the client's view.
