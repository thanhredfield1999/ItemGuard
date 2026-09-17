# P0 test round — ItemGuard LITE, 2026-09-16

Four gaps closed after the listing was already built. One real product defect found.

## The defect: in-hand tagging read the wrong slot

Shipped the previous day. `InventoryClickEvent.getSlot()` indexes the **clicked** inventory,
but the code read that index out of the **player's** inventory:

```java
final int slot = event.getSlot();                    // chest slot 5
plugin.getServer().getScheduler().runTask(
    plugin, () -> tagUntrackedInHand(player, slot));  // reads player slot 5
```

With a chest open, the clicked item was never tagged and an unrelated item at the same index
might be tagged instead. Neither produces an error.

It survived its own fixture because that fixture clicked in the hotbar with nothing open — the
one case where the two indices agree. **A single happy path is not coverage.**

Fixed with `ClickedSlotResolution`: read back only when the clicked inventory is the player's
own and the index is within 0-40. Otherwise do nothing and let the periodic sweep handle it —
a late tag is a small problem, tagging the wrong item corrupts the record. Six unit tests
(own inventory, container, click-outside `-999`, negative, armour/offhand 36-40, index past
40) plus a wiring test asserting the listener routes through it.

Java tests: **803/803**.

## New runtime fixtures

| Fixture | Verdict | What it establishes |
|---|---|---|
| `multi.py` | PASS_MULTI | Two tracked items get **distinct** identities; after a real handover the holder sees the ID and the non-holder is told "no tracking ID"; two concurrent `/ig check` agree |
| `reload.py` | PASS_RELOAD | `bukkit:reload` preserves the identity, re-enables with no errors, and leaks no listeners |
| `two_plugin.py` | PASS_TWO_PLUGIN | BastionForgeLite loaded alongside; a socket-shaped edit preserves the identity |
| `power_cut.py` | survived | Every DB row intact after `taskkill /F` mid-write |

Reload registry, before and after, identical:

```
ContainerListener=1;CraftListener=1;ItemListener=1;ItemLossListener=1;
LiteCommand=1;PaperEntitySpawnListener=1;PlayerListener=1
```

## Measurement mistakes made along the way

Recorded because each produced a confident wrong answer, and three would have been reported as
fact if not checked:

1. **`reload confirm` does nothing.** Paper routes a bare `reload` to vanilla's datapack
   command, which rejects `confirm` and never touches plugins. Needs `bukkit:reload confirm`.
2. **`has()` matched the boot line from before the reload**, returning instantly instead of
   waiting for the plugin to come back. Needs `has_more()` against a pre-reload count.
3. **The probe's remembered `code` was null after reload** — `bukkit:reload` constructs a new
   plugin instance. The probe assumed state survives the very event it was testing. Fixed by
   re-reading the code from the item, which also re-demonstrates why the ID lives in the item.
4. **Counting handlers per event, not per class.** Three different ItemGuard listeners
   legitimately handle `PlayerJoinEvent`; "3 handlers" is correct, not a leak.
5. **An absolute handler count cannot be judged at all.** A class with two `@EventHandler`
   methods for one event registers twice, legitimately. Only the count *changing across a
   reload* proves a leak.
6. **`setItem()` fires no Bukkit event**, so a probe-driven move wrote no history row. The
   fixture briefly read that zero as "no duplicate listener" — measuring nothing and calling
   it a pass. Replaced with the handler registry, which answers the question directly.

Mistakes 4-6 all share one shape: **a number was read before asking what the number means.**
See `skills/.../assertions-that-cannot-fail.md`.

## Consequence for release

`03cf8bbd` predates the slot fix. The jar in `release/spigot-upload/` carries the defect and
must be rebuilt, with every runtime gate and the 13-version matrix re-run, before upload.
