# Plugin compatibility — does an ID survive another plugin editing the item?

Asked by Thanh, 2026-09-16: *"nếu người chơi dùng plugin BastionForge đã khảm ngọc rồi thì nó
có lưu không?"*

**Short answer: yes with BastionForge, and the reason generalises.**

## The answer depends on the other plugin, not on ItemGuard

An ItemGuard identity lives in the item's persistent data container under the `itemguard`
namespace. Another plugin writing to the same item either leaves that container alone or
replaces it, and only one of those two patterns is safe:

| What the other plugin does | Identity survives |
|---|---|
| `item.editMeta(m -> ...)` — mutate in place | yes |
| `getItemMeta()` → change → `setItemMeta()` | yes |
| Rename, re-lore, change attribute modifiers | yes |
| `new ItemStack(...)` then copy selected fields | **no** |

The last row is the dangerous one, and it fails **silently**: nothing throws, the player sees
the same item with the same gem, and it has quietly stopped being traceable. It is invisible
until someone runs `/ig check` and gets nothing.

## BastionForge specifically: safe

Read from `bastion-lite/src/main/java/vn/heomc/bastionlite/bukkit/BukkitLiteItemPort.java:139`:

```java
item.editMeta(meta -> applyMeta(meta, stateKey, item.getType().getDefaultAttributeModifiers(),
        slot, state, language.get(), icons.get()));
```

and `applyMeta` writes exactly one key of its own:

```java
meta.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, encoded);
```

It mutates the meta it is handed and sets only `bastionlite:state`. No new `ItemStack` is
constructed anywhere in that module (`grep "new ItemStack"` returns nothing). ItemGuard's keys
are never in the path of that write.

So a socketed, re-lored, attribute-modified BastionForge item keeps its ItemGuard ID, and two
swords carrying the identical gem remain two distinguishable items.

## Pinned by tests

`src/test/java/com/itemguard/tracking/ForeignPluginPdcSurvivalTest.java` — six cases covering
the safe pattern, the destructive pattern, ten repeated gem upgrades, gem replacement, and the
property that matters most: **two swords with the same gem are still two different items**.

The destructive case is asserted as a *failure mode*, not as good behaviour. It exists so the
cost of that pattern is written down rather than rediscovered by a user.

## Scope of this evidence

These tests model the persistent data container as the key/value store it is, rather than
constructing a live `ItemStack`. Bukkit's `Registry` is not initialised outside a running
server, so `new ItemStack(Material.DIAMOND_SWORD)` throws `NoClassDefFoundError` in a unit
test — the same limitation already documented in `SnapshotVersionTwoDeterminismTest`.

That is the right level for this question: what is in doubt is whether writing key B removes
key A, which is a property of the container. It is **not** a live two-plugin runtime test —
BastionForge and ItemGuard have never been run on the same server together. Doing that would
need a fixture with both jars installed, and it is the honest next step if this compatibility
is ever claimed on the listing.

## What to tell a user who asks

> ItemGuard's ID is stored in the item itself, so cosmetic and upgrade plugins that edit an
> item in place — the normal way — keep it. A plugin that replaces an item with a freshly
> built copy will drop it, and there is no way for ItemGuard to detect that after the fact.
> If tracking stops working after installing something, that is the first thing to check.
