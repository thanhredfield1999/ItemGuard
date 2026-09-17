# Timeline icons and jump-to-chest (2026-09-12)

Two requests:

1. An entry should look like what it is: the item's own material when it is sitting in storage, and a
   player head when a player is carrying it.
2. Left-clicking an entry should teleport staff to that chest.

---

## Part 1 — Icons

### Rule

| The row says | Icon |
|---|---|
| Stored in a chest, barrel or ender chest (`CONTAINER_PUT`, `ENDERCHEST_PUT`) | the item's own material |
| A player is holding it (`PICKUP`, `CONTAINER_TAKE`, `SPAWN`, `INVENTORY_MOVE`) | that player's head |
| On the ground (`DROP`) | that player's head — they are who threw it |
| Died carrying it (`DEATH`) | that player's head |
| No actor recorded | Steve head (default) |

The item's own material is already known from the tracked record, so a stored row can always show the
real item.

### Skins must never block the server

A player head shows the right skin by setting the head's owner to that player's `OfflinePlayer`. The
client resolves the texture itself. This is what `PlayerBrowserGUI` already does, and it is the only
acceptable approach here.

**Never call a skin web API from the plugin.** Building a head from a fetched texture means an HTTP
request while rendering a GUI. On the main thread that stalls every player on the server; off the
main thread it needs caching, rate-limit handling and failure paths for a purely cosmetic gain. A
head with a name attached already renders the correct skin.

### Unknown actors

A row with no usable actor falls back to a plain Steve head. It must not fall back to the item
material, because that would make a held row look like a stored row.

### Privacy

Heads are a visual channel for the same information the text already carries. A member who may not
see another player's name must not be handed that player's face instead. When the viewer lacks
`itemguard.history.others` and the actor is somebody else, the icon is a plain Steve head with no
owner set.

---

## Part 2 — Left-click to jump

### What can go wrong

**T1 — Leaking base coordinates.** A chest position is exactly what a raider wants. If any player can
click a row and be taken to a stranger's chest, the plugin becomes a base-finding tool. Jumping is
staff-only, and only for rows the viewer is already allowed to see.

**T2 — Combat escape.** A free teleport is a get-out-of-fight card. Staff-only limits the blast
radius; beyond that, the jump is recorded so its use is auditable.

**T3 — Teleporting into solid rock or the void.** A chest can be walled in, or the coordinates can be
stale after terrain changes. Land on a verified safe position adjacent to the chest, or refuse.

**T4 — Loading distant chunks on demand.** Teleporting to an unloaded area forces a synchronous chunk
load and a visible server stall. Load asynchronously, then teleport.

**T5 — Stale coordinates.** The chest may be gone. Verify a container is actually there and say so
plainly when it is not, instead of dropping the player in an empty field with no explanation.

**T6 — Cross-world jumps.** The recorded world may not be loaded, or may be one staff should not
enter. Refuse when the world is not loaded.

### Rule

Left-click a timeline row that has a container position:

1. Requires `itemguard.teleport` (default: op).
2. Only for rows the viewer may already see — no jumping to a redacted row.
3. Only for rows whose position is a container (`CONTAINER_TAKE`, `CONTAINER_PUT`). Player positions
   are where somebody stood once and are not a place to go.
4. The world must be loaded, otherwise refuse and say which world.
5. The chunk is loaded asynchronously before the teleport.
6. The destination is a safe standing position next to the chest; if none exists, refuse.
7. The jump is logged with who jumped, to which item, and to which position.

Right-click keeps its current meaning. Left-click is the jump, as asked.

### Refusals

| Situation | Message |
|---|---|
| No permission | You do not have permission to jump to item locations. |
| Row has no container | That entry is not a container, so there is nowhere to jump to. |
| World not loaded | That world is not loaded right now. |
| No safe spot | There is no safe place to stand next to that container. |
| Redacted row | (the row is not clickable at all) |

---

## Scope

Both parts are display and navigation only. Neither changes what is recorded, so neither can affect
custody counting or duplicate detection.
