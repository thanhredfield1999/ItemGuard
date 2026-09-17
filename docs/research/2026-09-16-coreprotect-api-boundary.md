# CoreProtect: the boundary, verified from source

Date: 2026-09-16. Source: `github.com/PlayPro/CoreProtect`, Artistic-2.0, 986 stars, last push
2026-09-15 (actively maintained). 1,264,593 downloads on SpigotMC.

Until now the claim "CoreProtect cannot tell you whether two swords are the same sword" came
from reading its documentation. It can now be checked against its actual API surface.

## Every lookup is keyed by place, time or player

`CoreProtectAPI.java` exposes exactly these lookups:

```
blockLookup(Block, time)        containerLookup(Location, time)
entityLookup(options)           itemLookup(options)
inventoryLookup(options)        sessionLookup(user, time)
usernameLookup(user, time)      chatLookup / commandLookup / signLookup
```

And `LookupOptions` — the only way to parameterise any of them — carries these fields:

```
user            time            radius          location        world
limitOffset     limitCount      includeMaterials excludeMaterials
users           excludeUsers    containerActions itemActions
inventoryActions blockActions   sessionActions   entityActions
includeEntities excludeEntities
```

**There is no item identity field.** Not a UUID, not a code, not a per-object key. The finest
granularity available is `Material` — the *type* of item, not the item. `itemLookup` answers
"what diamond swords moved near here recently", never "where has *this* diamond sword been".

That is a design consequence, not an oversight: CoreProtect logs **transactions against
coordinates**. Two identical swords produce two identical-looking rows, and nothing in the
schema distinguishes them.

## What this means for ItemGuard's positioning

The line on the listing — that CoreProtect records actions by location and time and cannot
answer whether two existing items are the same object — is now backed by the API signature
rather than by reading marketing copy. It is a factual statement about a public interface.

Equally important, the reverse is true and should stay in the copy: CoreProtect rolls back
*blocks and containers* by area and time, which ItemGuard does not attempt at all. They sit
beside each other. A server running both loses nothing.

## The integration opportunity (Premium, not LITE)

CoreProtect is open source under Artistic-2.0 with a documented API, and `hasPlaced`,
`hasRemoved`, `performRollback` and `performRestore` are all public. A Premium edition could:

- attach an ItemGuard identity to a CoreProtect container transaction, so a staff member
  looking up a chest sees *which* item was taken, not just its material;
- use `containerLookup` to answer "where did this identity enter the world", filling in the
  history ItemGuard cannot see (closed containers it never swept).

**Licence note:** Artistic-2.0 permits compiling against the API as a soft dependency. It does
NOT permit copying source into this project. Any integration is a `Plugin` lookup plus API
calls, with the feature disabled when CoreProtect is absent — the same shape as the existing
Vault and WorldGuard hooks.

This is a Premium item and belongs after MySQL and restore in
`docs/release/TEST_PLAN_AND_PREMIUM.md`. It is not a LITE feature and must not be advertised
on the free listing.

## Not verified

- Whether CoreProtect's database could be joined to ItemGuard's without a performance cost.
- Whether server owners actually want the two linked, or would rather keep them separate. No
  user has asked for this; it is an observation about what is technically possible.
