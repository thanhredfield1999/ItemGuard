# LITE vs FULL — what the code actually does today

**Audited from source on 2026-09-15, not from marketing copy.** Every row has a file:line.
Nothing here is a plan; if a capability is listed as working, a call path was traced to it.

This document exists because a paid listing that advertises a capability the code does not
have is the most damaging thing this project could ship.

---

## The short version

**LITE is honest today.** Everything its listing claims, it does.

**FULL is not sellable today** — not because it lacks features, but because four advertised
capabilities have no working code behind them. Selling it as-is would be selling promises.

---

## Capability table

| Capability | LITE | FULL | Proof |
|---|---|---|---|
| Commands | `/ig` only | `/ig`, `/igcheck`, `/ighistory`, `/igsearch`, `/igstats`, `/finditem`, `/matdo` | `lite/plugin.yml:7-11` vs `plugin.yml:10-38` |
| Duplicate action | forced `NOTIFY` | reads config — but always resolves to NOTIFY anyway | `ConfigManager.java:104`, `ObservationEpochFinalizer.java:38` |
| Restore | absent | **permission declared, zero callers** | `plugin.yml:75-77`; `RestoreGate` has no caller in `src/main` |
| Reclaim `/matdo sos` | absent | pipeline runs, **always refuses** | `MatDoCommand.java:183-202` |
| Teleport | absent | **permission declared, word never appears in Java** | `plugin.yml:72-74` |
| WorldGuard | forced off | **works** — gates tracking on a region flag | `ItemTrackingService.java:604-628` |
| Discord | forced off | client exists, **never called** | `DiscordWebhook.java:32-54`, zero call sites |
| History cleanup | forced off (keeps everything) | configurable, 24h / 30 days | `ConfigManager.java:233-236` |
| GUI | small read-only menu | **works** — full catalog browser | `MainCommand.java:76-93` |
| Live search | ID lookup only | **works** — `/finditem` across players and containers | `commands/FindItemCommand.java`, `catalog/` |

---

## The four dead capabilities in FULL

These are not "unfinished". They are wired to nothing, and three of them are advertised in
`plugin.yml` where a server owner will read them.

### 1. Restore — a finished engine with no ignition

`RestoreGate` is a complete, tested, fail-closed decision engine. It has **no caller anywhere
in `src/main`** — only its own tests reference it. `itemguard.restore` is declared at
`plugin.yml:75-77` with a description promising the feature.

An admin can grant that permission and nothing will ever happen.

### 2. Teleport — declared, never written

`itemguard.teleport` at `plugin.yml:72-74` describes "teleport to a tracked item's container
location from the timeline". Grepping the entire Java source for `teleport` finds nothing.
The feature does not exist in any form.

### 3. Discord — a working client nobody calls

`DiscordWebhook.sendDuplicateAlert` is implemented and would work. Zero call sites. Setting
`discord.enabled: true` produces no behaviour at all.

### 4. Destructive anti-dupe actions — permanently downgraded

`DuplicateAction` has `REMOVE_NEWER`, `REMOVE_OLDER`, `REMOVE_ALL`. The only call site passes
`destructiveReleaseGateEnabled = false` as a **hardcoded literal**:

```java
// ObservationEpochFinalizer.java:38
DuplicateAction action = actionPolicy.resolve(configuredAction, false);
```

`AntiDupeActionPolicy.resolve` then downgrades any destructive choice back to `NOTIFY`
(`AntiDupeActionPolicy.java:9`). So `anti-dupe.action` in FULL's config **cannot do anything
but notify**, no matter what an owner sets.

This one is arguably correct as a safety decision — but then the config key should not offer
choices it silently ignores.

### 5. Reclaim — a pipeline with no success branch

`/matdo sos` parses, validates ownership, validates the snapshot, and probes for external
plugins. All real work. Then `runCapabilityPhase` (`MatDoCommand.java:183-202`) ends in one of
exactly two outcomes: blocked by evidence, or `ISSUANCE_GATE_CLOSED`. There is **no code path
in the entire `reclaim` package that returns an item to a player.**

---

## What LITE is missing on purpose vs. genuinely

Useful distinction, because "crippled" and "absent" sell differently.

**Gated by an edition check** (code is present in the jar and works):
- anti-dupe action → `ConfigManager.java:104`
- history auto-cleanup → `ConfigManager.java:235`
- WorldGuard → `ConfigManager.java:266`
- Discord → `ConfigManager.java:271`
- notify permission node swap → `InventoryScanTask.java:157`
- default language → `ConfigManager.java:46`

**Genuinely not wired in LITE**: every FULL command, the catalog browser, the full GUI stack.
Their compiled classes ride along in the shared jar (`package_lite.py:59`) but no command is
registered, so no permission can reach them.

LITE's own GUI is a separate smaller implementation, not a stripped copy of FULL's.

---

## What FULL honestly buys you today

More commands and a real GUI browser, `/igsearch` and `/finditem` live search across players
and containers, a working WorldGuard region gate, and configurable history retention. That is
a genuinely better **investigation toolset** for staff.

It does **not** buy item restoration, teleport-to-evidence, Discord alerting, or any
anti-dupe response beyond a chat message.

---

## Before FULL can be listed anywhere

1. **Delete `itemguard.restore` and `itemguard.teleport` from `plugin.yml`**, or implement
   them. Right now they advertise capabilities that do not exist — this is the single most
   damaging item on the page.
2. Decide on Discord: wire it up or remove the config section.
3. Decide on `anti-dupe.action`: either open the destructive gate deliberately, or reduce the
   key to a documented no-op so it stops implying a choice.
4. Either finish `/matdo sos` issuance or make its refusal message honest about being
   unavailable rather than evidence-blocked.
5. English command names — `/matdo` and `/finditem` are Vietnamese on an international market.
6. `config.yml` defaults to `language: vi`; `messages.yml` is unaccented Vietnamese.
7. No `package_full.py`, no FULL licence, no FULL README, and **no fixture has ever run
   against a FULL JAR**.

None of this blocks LITE. LITE's claims were audited separately and hold.
