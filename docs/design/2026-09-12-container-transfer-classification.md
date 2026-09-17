# Threat model — container transfers are invisible (2026-09-12)

Reported: taking an item out of a chest, a hopper, or an ender chest is all recorded as
`INVENTORY_MOVE`. Nothing names the actual container.

Written before fixing, because the documented rules depend on a distinction the code cannot make.

## Verified state of the code

- `ItemListener.onInventoryClick` writes exactly one action for every click on a tracked item:
  `INVENTORY_MOVE` (line 93). It does not look at which inventory was clicked, nor in which
  direction the item moved.
- `ItemTrackingService.onContainerOpen` is the only method that writes `CONTAINER_*`, and
  **nothing calls it**. Dead code.
- `CustodyActionPolicy` allowlists `CONTAINER_TAKE`, which therefore never occurs.
- `ContainerListener.onInventoryMoveItem` (the hopper path) cancels or scans but writes no history.

## Consequences

**C1 — A documented rule is false.** It was recorded that "taking from a chest counts as a
handover, hopper does not". Since chest takes are logged as `INVENTORY_MOVE`, and that action is not
in the custody allowlist, custody does **not** move. The rule as written does not hold.

**C2 — The main laundering route is unaudited.** Chest hand-off is how items actually change hands
on a server: A drops it in a chest, B takes it out, and neither player is ever near the other. The
history shows two `INVENTORY_MOVE` rows and the handover count stays flat, so the item's chain of
custody silently omits the transfer that matters most.

**C3 — Investigations cannot answer "where did it go".** With no container identity or coordinates,
staff cannot tell a chest at spawn from a shulker in a backpack from an ender chest. Two different
storage semantics collapse into one meaningless label.

## What must NOT be counted, and why

Fixing C2 naively — treating every container take as a handover — creates new abuse:

**T1 — Ender chest is private, per player.** Only the owner can ever see their own ender inventory.
An item put in and taken back out never left that player. Counting it would let one player inflate
the handover count and the holder list alone, forever, with no second party. It must be recorded
distinctly and must not move custody.

**T2 — Shulker boxes and other containers opened from a player's own inventory** are carried
storage. The item stays with the same holder. Same conclusion as T1.

**T3 — Hoppers, droppers and other machines have no actor.** A transfer with no player must never
be attributed to a player name, or an attacker can arrange for someone else to appear in a chain of
custody they never touched. This currently cannot happen because the hopper path writes no history;
that property must be preserved deliberately, not by accident.

**T4 — Direction confusion is worse than no data.** If a put is recorded as a take, custody moves to
the player who gave the item away. That would produce confident, wrong attribution. Direction must
be derived from which inventory the item ended up in, not guessed.

**T5 — Chest cycling by one player.** A taking their own item out of their own chest repeatedly must
stay one holder. Custody already only moves when the holder changes, so this is safe, and the write
gate bounds the row count.

## Decision

Classify the transfer at the click boundary, from the inventory the item moved out of and into:

| From | To | Action | Moves custody |
|---|---|---|---|
| block container (chest/barrel/shulker placed in world) | player inventory | `CONTAINER_TAKE` | yes |
| player inventory | block container | `CONTAINER_PUT` | no (a release, not a claim) |
| ender chest | player inventory | `ENDERCHEST_TAKE` | no (private, same holder) |
| player inventory | ender chest | `ENDERCHEST_PUT` | no |
| player inventory | player inventory | `INVENTORY_MOVE` | no |
| machine, no player | — | not written | n/a |

Container coordinates go in the location column that history already carries, so staff can see
*which* chest.

Constraints: additive only, no schema change; the hopper path keeps writing nothing; every new
action must be an explicit allowlist decision, not a default.
