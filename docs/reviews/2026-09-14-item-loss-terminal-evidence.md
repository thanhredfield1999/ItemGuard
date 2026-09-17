# ItemLossListener — damage-cause loss recorded while the item survives

Date: 2026-09-14
Scope: `src/main/java/com/itemguard/listeners/ItemLossListener.java`,
`src/main/java/com/itemguard/listeners/ItemLossPolicy.java`.
Evidence level: offline Java unit + package/artifact verifier. **No Paper runtime was launched.**

## Symptom

`onGroundItemDamaged` ran at `MONITOR`/`ignoreCancelled` and called
`tracking.recordLoss(...)` the moment an `EntityDamageEvent` on a ground `Item` carried
`FIRE`, `FIRE_TICK`, `LAVA` or `VOID`. Nothing checked whether the item entity actually
stopped existing.

## Why this is a correctness bug, not a mislabel

Every reason `ItemLossPolicy.forEntityDamage` can return reports
`LossReason.confirmsDestruction() == true` (`BURNED`, `VOID`). A confirmed-destroyed identity
is the input a restore decision acts on. Writing that row for an item still lying on the
ground yields the contradiction the anti-dupe design exists to prevent: the item exists in the
world **and** the database says it was destroyed. This is the same class of defect already
recorded in `DeathIsNotAClearRegressionTest` (a false `CLEARED` row for loot on the ground).

## Root cause, with pinned-API evidence

Damage is survivable for an item entity, so a damage cause is not a terminal signal.
Verified against the exact dependency in use,
`paper-api-1.21.11-R0.1-20260511.115010-91.jar` (`~/.m2/.../io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT`):

- `org.bukkit.entity.Item` exposes `getHealth()` / `setHealth(int)`. Paper javadoc for 1.21.11:
  *"Currently the default max health is 5"*, and *"a non-positive value will destroy the entity"* —
  i.e. an item entity has health and survives damage that does not drive it to zero.
- `EntityRemoveEvent.Cause.OUT_OF_WORLD` is documented as
  *"When an entity gets removed because it is too far below the world. This only applies to
  entities which get removed immediately, **some entities get damage instead**."* So `VOID`
  damage explicitly does not imply removal.
- `org.bukkit.event.entity.EntityRemoveEvent` **is present and not deprecated** in this exact
  jar (`javap -v` shows no `Deprecated` attribute on the class or on `getCause()`), and its
  `Cause` enum in this jar contains `DISCARD` in addition to the values on the Spigot docs page.

`EntityRemoveEvent` is therefore the terminal signal the architecture was missing. It was
previously unused anywhere in the repo (`grep` over `src/main`, `src/test` returned nothing).

## Fix (conservative; grants no restore)

1. `onGroundItemDamaged` no longer records anything. It remembers the classified reason against
   the item entity's UUID in a `pendingDamage` map.
2. New `onGroundItemRemoved(EntityRemoveEvent)` at `MONITOR` consumes that note. It records the
   loss **only** when the removal cause is consistent with the damage having destroyed the item.
3. `ItemLossPolicy.destroysDamagedItem(String)` is an allowlist — `DEATH`, `DISCARD`,
   `OUT_OF_WORLD` — and fails closed on anything else, including `null` and unknown future
   causes. `PICKUP`, `MERGE`, `UNLOAD`, `PLUGIN` and `DESPAWN` all mean the stack still exists.

The pending note is removed on every removal for that entity, so it cannot be inherited by a
later removal. No new restore path, permission, command or issuance was added; `LossReason`
and its `confirmsDestruction` contract are unchanged.

### Deliberately not done

`ItemDespawnEvent` was left alone. It is a distinct, already-terminal Bukkit signal for the
despawn timer, not a damage inference, so it is out of scope for this defect.

## Tests

New `src/test/java/com/itemguard/listeners/ItemLossTerminalEvidenceTest.java` (4) plus 3 new
cases in `ItemLossPolicyTest`.

RED was observed for the real reason before the fix:

```
[ERROR] Tests run: 1, Failures: 1, Errors: 0 -- in ItemLossTerminalEvidenceTest
itemTrackingService.recordLoss(
Never wanted here:
-> at com.itemguard.services.ItemTrackingService.recordLoss(ItemTrackingService.java:755)
```

Two harness defects were found and fixed while building the loop, and are worth recording
because both would have produced a fake pass:

- Constructing a real `ItemStack` or a real `EntityDamageEvent` offline throws
  `IllegalStateException: No RegistryAccess implementation found`. Both are mocked.
- A typed `verify(..., never()).recordLoss(any(ItemStack.class), ..., any(Location.class), ...)`
  passed against the buggy code, because Mockito's typed `any(Location.class)` does not match
  the `null` location a mocked entity returns. Untyped `any()` is required for these assertions.

Non-vacuity was proven by mutation rather than asserted. Adding `"PICKUP"` to the
`destroysDamagedItem` allowlist failed exactly the three tests that must catch it:

```
[ERROR] ItemLossPolicyTest.aRemovalThatLeavesTheItemExistingIsNotDestruction:59 expected: <false> but was: <true>
[ERROR] ItemLossTerminalEvidenceTest.aRememberedDamageIsNotHeldAgainstALaterUnrelatedRemoval:117
[ERROR] ItemLossTerminalEvidenceTest.anItemDamagedThenPickedUpIsNeverRecordedAsLost:96
[ERROR] Tests run: 13, Failures: 3
```

The mutation was reverted and the source restored before the full gate.

## Verification run on this tree

```
export JAVA_HOME='C:/Program Files/Java/jdk-21'
./mvnw.cmd -o clean test --no-transfer-progress
  -> Tests run: 651, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS

python -m unittest discover -s scripts -p 'test_*.py'
  -> Ran 10 tests, OK

python scripts/package_lite.py       # runs Maven clean verify itself
  -> tests 651/0/0/0, runtime_verified: false
  -> target/ItemGuard-LITE-1.0.0.jar
     sha256 cd8626b2ab22a0e4b2a70bc6ff0e1c6dcd92680af0beb2817bf5ac23cece2147
     source_jar_sha256 9c36a08dddedc18178a6fe062903d4d993006310924f0ff47bacc58bd55260db

python scripts/verify_lite_artifact.py
  -> VERDICT ARTIFACT_CONSISTENT_OFFLINE_ONLY (exit 0)
     entries 533, classes 418, main com.itemguard.lite.ItemGuardLite,
     api_version 1.21.11, commands [itemguard], duplicate_action NOTIFY
```

Java test count moved 644 -> 651 (+7 new, no deletions).

## Limits — what this does NOT prove

- `OBSERVED`/`VERIFIED` at the unit and artifact layer only. No Paper server was started.
- The precise `EntityRemoveEvent.Cause` Paper emits when an item entity burns to death in lava
  is `INFERRED` from the API contract, not measured. The allowlist covers `DEATH`, `DISCARD`
  and `OUT_OF_WORLD` for that reason; if the real runtime cause is outside that set the
  behaviour fails closed (a genuine burn goes unrecorded) rather than open (no false loss).
  Confirming which cause fires needs a controlled Paper fixture and is still open.
- Ordering of `EntityDamageEvent` before `EntityRemoveEvent` for the same entity is assumed
  from Bukkit semantics; not runtime-measured.
- `pendingDamage` is bounded only by removals. A damaged item whose entity is never removed
  while the server runs leaves one map entry. Sized like `lastSeen`; not load-tested.
