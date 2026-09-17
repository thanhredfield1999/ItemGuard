# Loss and restore — operator reference

## What gets recorded, per action

| What the player did | Recorded as | Position stored | Counts as changing hands |
|---|---|---|---|
| Picked up off the ground | `PICKUP` | the player | yes |
| Threw it on the ground | `DROP` | the player | no |
| Took it out of a chest or barrel | `CONTAINER_TAKE` | **the chest** | yes |
| Put it into a chest or barrel | `CONTAINER_PUT` | **the chest** | no |
| Took it out of their ender chest | `ENDERCHEST_TAKE` | the player | no |
| Put it into their ender chest | `ENDERCHEST_PUT` | the player | no |
| Took it out of a shulker in their bag | `CARRIED_CONTAINER_TAKE` | the player | no |
| Put it into a shulker in their bag | `CARRIED_CONTAINER_PUT` | the player | no |
| Moved it between their own slots | `INVENTORY_MOVE` | the player | no |
| Died carrying it | `DEATH` | where they died | no |
| First time tracked | `SPAWN` | the player | starts the chain |
| Admin gave it back | `RESTORED` | the recipient | no |

A hopper, dropper or other machine moving an item writes **nothing**. There is no player to name, and
naming one would be false evidence.

Why ender chests and carried shulkers do not count as changing hands: both are private to the player
who opened them. If they counted, one player alone could inflate their own handover count and holder
list indefinitely, with no second party involved.

## Repeated identical actions

Doing the same thing to the same item repeatedly does not add a row each time. The existing row's
time moves forward and a repeat counter rises. A different player, a different action, or a gap over
a minute always writes a new row.

This exists so that spamming cannot bury a real transfer: history windows are bounded, and without
it a player could push the incriminating row out of view.

## When an item counts as destroyed

All four must hold:

1. A scan did not find it anywhere.
2. That scan completed a full pass.
3. That scan could read every holder it needed — no unloaded chunks in the way.
4. The last thing recorded was a release: `DROP`, `CONTAINER_PUT`, or `DEATH`.

Absence alone is never enough. An item inside an unloaded chunk or a logged-out player's inventory is
invisible and perfectly intact; treating that as destroyed would hand out free duplicates.

Ender chest contents are never declared destroyed, because a scan cannot read an ender chest, so
their absence means nothing.

Destroyed is a recorded state, not a deletion. The history stays, which is what makes a later restore
auditable.

## Restoring an item

```
/ig restore <code> <player> <reason>
```

Allowed only when **all** of these hold:

1. The code exists in the records.
2. Its state is destroyed.
3. It has never been restored before — one restore per item, ever.
4. The most recent completed scan did not find it.
5. That scan is the same one that confirmed the loss, not an older one.
6. The operator has `itemguard.restore`.

Any failure refuses and names which condition failed. Permission is checked first, so someone without
it learns nothing about an item's state.

A restore returns the **same** code and the **same** internal identity — the item resurrected, not a
copy. It writes a `RESTORED` row naming the operator, the recipient and the reason, and marks the
identity as restored forever. Custody moves to the recipient without counting a handover, because an
administrative correction is not a player-to-player trade.

### Refusal messages

| Situation | Message |
|---|---|
| No such code | No such tracked item, so there is nothing to restore. |
| Item still exists | That item still exists. Restoring it would create a second copy. |
| Restored before | That item has already been restored once. It cannot be restored again. |
| Loss unconfirmed | The loss has not been confirmed by a completed scan yet. |
| Newer scan since | A newer scan has run since the loss was confirmed. Confirm the loss again. |
| Still found | The latest scan still found that item, so it is not lost. |
| No permission | You do not have permission to restore items. |

## Edition split

LITE records everything above, confirms destruction, and refuses restores with the correct reason.
Issuing the item back is a FULL feature: handing items to players affects the server economy and
needs the guardian machinery. LITE refuses honestly rather than pretending the command is absent.
