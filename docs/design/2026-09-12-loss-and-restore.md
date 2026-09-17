# Loss and restore — exact procedure (2026-09-12)

Two requirements, taken literally:

1. History must say **where** an item was put, not just that it moved.
2. If an item is destroyed — burned, cleared, lost — an admin must be able to give it back, and that
   path must not become a duplication tool.

Written before any code. Restoring items is the single easiest way to introduce duplication into a
plugin whose whole purpose is preventing it.

---

## Part 1 — Where

### Current behaviour (verified)

`ItemTrackingService.onItemMoveInInventory` line 660 and `logHistory` both record
`player.getLocation()`. For a chest transfer that is where the **player stood**, not where the chest
is. A player can stand in one place and reach several containers, and can move away immediately.

### Decision

For any transfer involving a container, record the **container's** block position. For transfers with
no container, keep the player position. One rule, stated per action:

| Action | Position recorded |
|---|---|
| `CONTAINER_TAKE` / `CONTAINER_PUT` | the container block |
| `ENDERCHEST_TAKE` / `ENDERCHEST_PUT` | the player (an ender chest has no single location) |
| `CARRIED_CONTAINER_*` | the player (it is in their inventory) |
| `PICKUP` / `DROP` | the player |
| `DEATH` | the player's death position |

Hoppers and other machines write nothing at all, so they have no position to record. A machine
transfer is not attributable to any player, and inventing one would be false evidence.

---

## Part 2 — Destruction

An item stops existing when it burns in lava or fire, is cleared by a command or plugin, despawns on
the ground, or is consumed. The tracked identity must not be left looking as though someone still
holds it.

### What already exists

The scan machinery has complete epochs: `item_observations` plus `epoch_complete`. A completed epoch
is a full sweep of every loaded holder. If an identity is absent from a completed epoch, it was not
anywhere the sweep could see. That is the only honest basis for declaring an item gone — a single
missed tick is not.

### Rule

An identity is marked `DESTROYED` only when **both** hold:

- it is absent from a **completed** observation epoch, and
- its last recorded action is a releasing action (`DROP`, `CONTAINER_PUT`, `DEATH`) or it was
  observed in a container that no longer exists.

Absence alone is never enough: chunks unload, players log out, and an item inside an unloaded chunk
is invisible but perfectly intact. Marking those destroyed would hand out free duplicates on demand.

`DESTROYED` is a **recorded state, not a deletion**. The history rows stay. This is what makes a
later restore auditable.

---

## Part 3 — Restore

### The duplication risk, stated plainly

If an admin can restore an identity that still exists somewhere, there are now two. Every rule below
exists to make that impossible.

### Preconditions, all required

1. The identity exists in the database.
2. Its state is `DESTROYED`.
3. It has **never been restored before** — one restore per identity, ever.
4. The most recent completed observation epoch does **not** contain it.
5. The admin holds the restore permission.

Failing any precondition refuses the restore and says which one failed. Fail-closed: an unknown state
refuses.

### What a restore does

- Issues the item to the target player with the **same** public code and the **same** item UUID. Not
  a copy with a new identity — the same identity resurrected, so the audit trail is continuous.
- Writes a `RESTORED` history row naming the admin, the recipient, and the reason.
- Marks the identity as restored, permanently. The second attempt is refused by precondition 3 even
  if the item is destroyed again later.
- Sets custody to the recipient without counting a handover: an administrative correction is not a
  player-to-player transfer and must not inflate the count.

### What a restore must never do

- Never restore an identity in any state other than `DESTROYED`.
- Never issue a new UUID for an old code, or a new code for an old UUID. Either would create a
  second identity for one item.
- Never restore twice.
- Never act on absence that was not confirmed by a completed epoch.
- Never silently succeed when the recipient's inventory is full: hold or refuse, and say so.

### Admin-facing surface

```
/ig restore <code> <player> <reason>
```

One command. It states the identity's current state, the epoch that confirmed the loss, and refuses
with a specific reason when a precondition fails.

---

## Scope

LITE gets the recording and the refusal logic: destruction state, positions, and the audit row. The
issuing half of restore is a FULL feature, because handing items back is an economy-affecting
operation that needs the guardian machinery. LITE must still **refuse** correctly rather than pretend
the feature is missing.
