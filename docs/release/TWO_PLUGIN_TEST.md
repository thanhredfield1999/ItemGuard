# Two plugins, one item — ItemGuard LITE + BastionForgeLite

Date: 2026-09-16. Fixture: `tools/lite-runtime/two_plugin.py`.

Asked by Thanh: *"BastionForge item đã test trên ItemGuard chưa?"* Before this run the answer
was **no** — the compatibility claim rested on reading BastionForge's source and on unit tests
that modelled the persistent data container as a map. Neither had ever put both jars on one
server.

## Setup

`bastion-lite-0.1.0.jar` installed into the fixture's `plugins/` next to ItemGuard LITE
(`c5cdfb6f`), single Paper server. The fixture **refuses to continue** unless BastionForgeLite
actually enables — without that gate every later assertion would pass for the wrong reason,
since nothing would be editing the item at all.

```
forge_enabled: true
```

## Result

| | Before socket | After socket | Verdict |
|---|---|---|---|
| Identity | `AIBQ7Z` | `AIBQ7Z` | unchanged — correct |
| Digest | `ccd33332…de4a` | `d4ad8798…8795` | changed — correct |

`/ig check` still found the item by its original code (`check_saw_code: true`).

**Both outcomes are the intended ones, and they are different on purpose:**

- **The identity must not change.** It is the same physical sword. A second identity would
  make one item look like two, which is precisely the failure this plugin exists to prevent.
- **The digest is supposed to change.** It hashes the whole item, and the item genuinely did
  change — new lore, a new attribute modifier, a new PDC key. What matters is that the
  identity survives to tie the two snapshots together. Without it, duplicate detection on a
  socketed item would have nothing to compare against.

An earlier worry — that socketing might cause ItemGuard to treat the item as new and mint a
second identity — did not happen.

## Why it works, from BastionForge's source

`bastion-lite/.../bukkit/BukkitLiteItemPort.java:139`:

```java
item.editMeta(meta -> applyMeta(meta, stateKey, ...));
// applyMeta: meta.getPersistentDataContainer().set(stateKey, STRING, encoded);
```

It mutates the meta it is handed and writes only `bastionlite:state`. `grep "new ItemStack"`
across that module returns nothing, so no code path rebuilds the item and drops foreign keys.

## Scope — what this run does and does not prove

**Does:** both plugins load together; a BastionForge-shaped edit on a tracked item preserves
the ItemGuard identity; `/ig check` still resolves it afterwards; the digest moves
independently of the identity.

**Does not:** the socket was applied by the probe reproducing BastionForge's exact write
pattern, **not** by clicking through the forge GUI — menu interaction is not something a bot
drives reliably. So this is a test of the *interaction* with BastionForge installed and
enabled, not of BastionForge's menu flow. Driving the real GUI needs a human at a client.

Also untested: gems as separate items (they stack, so ItemGuard ignores them by design),
BastionForge's reforge and dismantle paths, and any third plugin.

## The general rule for users

ItemGuard's ID lives in the item. A plugin that **edits an item in place** keeps it — that is
the normal pattern and what BastionForge does. A plugin that **replaces the item with a freshly
built copy** drops it silently: no error, same-looking item, tracking quietly gone. If tracking
stops working after installing something, that is the first thing to check.
