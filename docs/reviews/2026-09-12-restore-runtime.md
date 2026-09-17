# Restore command — runtime evidence (2026-09-12)

JAR `715a46f68bca2d4b6acc5e676657283321958f4e8d36e2fb7a5c6985f7ce9a29`, 629/629 unit tests.
Driven by `tools/lite-runtime/restore-probe.cjs` against a real Paper server, via a real client.

## What the client saw

| Step | Command | Reply |
|---|---|---|
| Unknown code | `/ig restore #ZZZZZZ IGRestore testing` | No such tracked item, so there is nothing to restore. |
| Item in hand | `/ig restore #AJ9L8D IGRestore testing` | That item still exists. Restoring it would create a second copy. |
| After `/clear` | `/ig restore #AJ9L8D IGRestore testing` | The loss has not been confirmed by a completed scan yet. |
| No arguments | `/ig restore` | Usage: /ig restore #ID &lt;player&gt; &lt;reason&gt; |

Database after the run:

```
history: SPAWN, SPAWN, CLEARED, CLEARED
epochs : count=0, max_scan_epoch=0
```

## Why the third row is correct, not a bug

The item was genuinely cleared — the `CLEARED` rows prove the loss was recorded. The restore was
still refused because **no completed observation epoch exists on this server**, so nothing has
independently confirmed the item is absent.

That is the rule from `docs/design/2026-09-12-loss-and-restore.md` working as intended: a history row
says what one code path believed, while a completed sweep is independent confirmation. Accepting the
history row alone would mean any plugin that writes a loss row could unlock a free duplicate.

A restore therefore becomes possible only once the anti-duplicate scan has run and still not found
the item. On this fixture the scan never ran, so the honest answer is refusal.

## Verdict coverage

Six of the seven refusal reasons are now exercised:

- `UNKNOWN_IDENTITY` — runtime, above
- `NOT_DESTROYED` — runtime, above
- `NOT_CONFIRMED_LOST` — runtime, above
- `NO_PERMISSION` — unit (`RestoreGateTest`), checked before anything else so a non-admin learns
  nothing about an item's state
- `ALREADY_RESTORED` — unit; derived from a `RESTORED` history row, so it survives restarts
- `STALE_CONFIRMATION` — unit
- `SEEN_IN_LATEST_EPOCH` — unit only; needs a fixture where a scan completes and finds the item

## Not covered

The allowed path has no runtime evidence, because reaching it needs a completed scan that does not
find the item. LITE would only print "restorable" anyway: issuing the item back is a full-edition
feature, since handing items to players affects the server economy.

## Harness defects found and fixed

1. `handleRestore` called a synchronous database method from an async `whenComplete` callback. The
   command produced **no reply at all** — the failure was swallowed by the future. Now routed through
   the existing `query()` helper, which returns to the main thread.
2. `query()` only checks the permission inside its callback, so a player without it got silence.
   Permission is now refused before the query starts; a silent refusal is indistinguishable from a
   broken command.
3. The probe filtered chat on the `ItemGuard` prefix, but the `#CODE | latest: ...` line has no
   prefix, so the code was never captured.
4. An identity is assigned when an item is handled, not when it arrives in the inventory, so the
   probe had to equip the pickaxe before it had a code.
