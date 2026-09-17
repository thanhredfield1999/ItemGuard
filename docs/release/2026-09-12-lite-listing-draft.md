# ItemGuard LITE — SpigotMC listing draft (NOT PUBLISHED)

Status: DRAFT awaiting owner approval. Nothing in this document authorizes publishing. Do not upload, and do not copy any line below that this repository cannot evidence.

---

## Resource title

ItemGuard LITE — Item ID & History Inspector (Paper 1.21.11)

## Tagline

Give every non-stackable item a permanent ID and a readable history, so staff can investigate instead of guess.

## Supported platform (state exactly this, nothing wider)

- Server: **Paper 1.21.11**. Java 21. Nothing else is supported.
- Not verified on: vanilla Spigot, Folia, or any other Minecraft version. SpigotMC is only the marketplace; it is not a vanilla Spigot compatibility claim. Older-version source compilation exists but is **not** runtime support, so do not advertise `1.21.x+`.
- The LITE descriptor declares `api-version: '1.21.11'`, plugin name `ItemGuard`, main class `com.itemguard.lite.ItemGuardLite`, version string `1.0.0-lite`.
- Storage: SQLite, created inside the plugin folder.
- Built with **Maven** (`mvnw.cmd clean verify`), then repackaged into the LITE artifact by `scripts/package_lite.py`. This project has no Gradle build.
- Never install ItemGuard LITE and ItemGuard Full together: they share the plugin name `ItemGuard` and the same PDC namespace.

## What it does

- Assigns a tracked identity to non-stackable items and records events (first tracked, picked up, dropped, used, moved, container activity, death).
- Registers exactly one command, `/itemguard`, with the alias `/ig`. LITE registers no other command.
- `/ig check` — read the ID of the held item. It only reads an existing tag; it does not create an ID just for checking.
- `/ig history` — your own recent activity, grouped as one row per item, drawn from a window of your **45 most recent recorded events**. That window is a cap on loaded events, not a lifetime total, and the command says so on screen.
- `/ig gui` — a read-only paged menu of **your own** recorded items, **28 entries per page** in a 54-slot inventory (row 1 guide, rows 2–5 content, row 6 paging). Clicking an entry opens that item's timeline, which renders up to 28 rows from the same 45-event window. All clicks and drags in these menus are cancelled; nothing can be taken out.
- `/ig history #ID` — a member may drill into **one of their own** items; that query stays owner-scoped. Investigating an ID held by someone else requires `itemguard.history.others`.
- `/ig search #ID` — staff-only lookup of one exact item ID. It requires `itemguard.history.others`; `itemguard.search` alone returns nothing useful.
- `/ig stats` — staff database statistics (tracked items, recorded history rows, duplicate findings, database type and status).
- Any unrecognised argument, including `/ig help`, prints the usage line.
- Duplicate detection: when the same identity is observed in two places within one **completed inventory scan**, the server log records `ITEMGUARD_DUPLICATE_CONFIRMED` with the code, scan epoch and distinct location count, and online staff holding `itemguard.notify` receive a chat warning. A detection cooldown limits repeat alerts.
- **What the scan sees:** online players' inventories, plus a sweep of every container in chunks that are **already loaded** — chests, barrels, shulkers, hoppers — open or closed. Two copies hidden in two closed chests in a loaded area do raise a warning.
- **What it still does not see:** the sweep never loads a chunk (a monitoring plugin that forces chunk loads becomes the reason a server lags), so a stash in a region nobody visits is not compared. Ender chests, minecart and animal inventories, and offline players remain out of scope. Please state this honestly rather than advertising total coverage.
- **Cost control:** the sweep is spread over many ticks with a bounded chunks-per-tick budget, one pass finishing before the next starts. `anti-dupe.sweep.enabled` and `anti-dupe.sweep.chunks-per-tick` let an owner turn it down or off without waiting for a release.
- Handover counting: each item's timeline shows how many times it genuinely changed hands. Throwing your own item on the floor and picking it back up does not count. Two players swapping the same item back and forth repeatedly does not inflate the number either.

## What it deliberately does NOT do

- **Never deletes, confiscates, moves, or grants items.** The duplicate action is forced to `NOTIFY` in code for the LITE edition; an edited `config.yml` cannot enable removal.
- No reclaim, withdraw, lost-item recovery, **teleport**, or external-storage lookup. No such command is registered, so no permission node — including a wildcard grant — can reach one.
- No general text search and no live world scan. `/ig search` takes an exact ID.
- No WorldGuard and no Discord integration: both are forced off in code for LITE regardless of `config.yml`.
- Automatic history deletion is disabled in code for LITE (the cleanup interval is forced to 0), so your test history is not pruned underneath you.

## Metrics / telemetry — disclose this, do not claim "no telemetry"

- ItemGuard LITE **does** initialise bStats (`bstats-bukkit`, shaded and relocated into the JAR) on enable, using bStats plugin ID **34029**, shared with ItemGuard Full.
- Beyond standard bStats server/plugin data it sends three custom charts: `edition` (reports `lite`), `language`, and `anti_dupe_mode`.
- **There is no per-plugin metrics toggle in ItemGuard's `config.yml`.** The only opt-out is the server-wide bStats configuration at `plugins/bStats/config.yml`: set `enabled: false` and restart, which turns collection off for **every** bStats plugin on that server.
- Do not advertise an ItemGuard-specific opt-out switch; this build does not have one.

## Honest limits (keep these in the listing)

- Recorded history is not live custody. The last recorded location is where the item **was** seen, not where it is now.
- Missing history is not proof an item never existed. Tracking only covers what the plugin observed while installed.
- The oldest line in a timeline is only the oldest line inside the loaded window; it is not proof of the item's origin.
- A member's history view is scoped to the item's current holder. Events recorded by a previous holder stay visible as events, but that player's name and coordinates are withheld unless the viewer has `itemguard.history.others`.
- **ItemGuard LITE is not a read-only plugin.** It writes identity tags into item PDC and periodically scans player inventories and open containers (LITE defaults: scan interval 600 ticks, container scanning on). Only the `/ig gui` menu is read-only: clicks and drags inside the menu are cancelled. The plugin itself is a writer — a non-destructive one, but a writer. Do not describe the plugin as read-only anywhere in the listing.
- Crafting whose output would need a new tracked identity is currently **cancelled fail-closed**, and the refusal message is non-localized — it is shown in the same fixed wording regardless of the configured language. What has been observed is the **cancellation**; **issuance of a tracked identity through crafting has never been verified at all**. This is a deliberate safety stance, not a finished crafting feature, and it must not be advertised as crafting support.
- This is not a complete anti-dupe guarantee. It records and warns; it does not promise prevention.

## Permissions

Taken from the LITE descriptor, `src/main/resources/lite/plugin.yml`.

| Node | Default | Grants |
|---|---|---|
| `itemguard.check` | everyone | read the held item's ID |
| `itemguard.history` | everyone | own recent history, own-item drill-down, and the read-only GUI |
| `itemguard.history.others` | op | investigate any ID, and see other players' names/locations |
| `itemguard.search` | op | exact-ID lookup (also requires `history.others` to return anything) |
| `itemguard.stats` | op | database statistics |
| `itemguard.notify` | op | receive duplicate warnings |
| `itemguard.admin` | op | declared parent of all of the above |

## Installation

1. Stop the server. Do not use `/reload`, PlugMan, or any hot-loader.
2. Drop the JAR into `plugins/`.
3. Start once so `plugins/ItemGuard/config.yml` is generated, then **stop the server again** before editing it (`general.language: en` or `vi`).
4. Start again.

## Backing up the database (server stopped)

The database is a plain SQLite file at `plugins/ItemGuard/itemguard.db`. ItemGuard keeps an open connection to it while the server runs, so a copy taken from a live server is not a safe backup.

1. **Stop the server fully** and wait for the process to exit. Do not use `/reload` and do not copy while the server is running.
2. Copy `plugins/ItemGuard/itemguard.db` to your backup location. If sidecar files exist next to it (`itemguard.db-journal`, `itemguard.db-wal`, `itemguard.db-shm`), copy them in the same step — never take the `.db` on its own.
3. Keep the copy until the upgrade is confirmed good. Restoring means putting the same set of files back, again with the server stopped.

Do this before every upgrade.

## Language

English by default for LITE (`general.language` defaults to `en` in this edition); Vietnamese via `general.language: vi`. Vietnamese presentation has not yet passed owner visual acceptance, so do not advertise it as polished. The fail-closed craft refusal message is not translated in either language.

## Verification status to disclose

State the current candidate honestly. Do not present an earlier run as proof about the artifact being published.

**Do not cite the build receipt's `runtime_verified` field as runtime evidence — in either direction.**
`scripts/package_lite.py` writes `'runtime_verified': False` as a hardcoded literal at package time, and `scripts/verify_lite_artifact.py` rejects the receipt if the field is anything other than `false`. Neither ever inspects a server. The field is a packaging constant that records nothing about whether Paper was started, so it can neither prove nor disprove runtime verification. Earlier wording in this draft used it as proof that the candidate was unverified; that citation was wrong and has been removed. Use the fixture evidence below instead.

**Superseded candidate.** `e11ada6a626294f86fb74d31caebff4354bbfe7f7ac5b9f65bcc763113e9e53b`, together with fixtures `itemguard-lite-isolated-1be196598fb7` and `itemguard-lite-isolated-52f4cc13610e`, is **SUPERSEDED**. Two post-review defects were fixed in product source after that candidate was packaged, so its hash and its fixture passes no longer describe anything publishable. Do not quote those figures anywhere.

**Defects closed since the superseded candidate.**
- Item-entity handlers now ignore events after stop, so shutdown can no longer throw from a Bukkit handler.
- A code whose tracked row was pruned is now retired once, instead of being retried on every scan forever.

**Current candidate — offline evidence.**
- Candidate SHA-256 `a952d16146082ffacfbcbf6032fdba0634c86e8099cbf6802685c2f76db71727`.
- Java test gate on the packaged artifact's source under Java 21: **755/755**, zero failures, errors and skips.
- Artifact verifier `scripts/verify_lite_artifact.py`: verdict `ARTIFACT_CONSISTENT_OFFLINE_ONLY` over **546 JAR entries** (431 classes). This covers entry set, CRC, allowed byte substitutions, descriptor entry point, `api-version`, command/permission surface and default config only. The verifier prints its own scope: *not runtime, client, gameplay, release or production evidence*.

**Current candidate — fresh Paper 1.21.11 / Java 21 runtime, on THIS candidate.**
Two isolated fixtures were run on the exact candidate above and are now consumed; no replay: `itemguard-lite-isolated-57c97c1ee2be` and `itemguard-lite-isolated-dd70b72bb201`.
- Terminal-loss protocol, fixture `itemguard-lite-isolated-57c97c1ee2be`: **`PASS_LOSS_ONLY_SMOKE`**. It includes both crafting-close cases — spare inventory and full inventory — each ended by the real client's own close (close reason `PLAYER`, recorded as `closeAck=CLIENT`), with the tracked code and UUID intact afterwards and no loss row and no `CLEARED` row written for a stack that survived.
- Full controlled protocol, fixture `itemguard-lite-isolated-dd70b72bb201`: **`PASS_CONTROLLED_SMOKE`**, two generations.
- Cleanup: all child processes exited 0, none forced, and the ports were released.
- Scope limits carried by the loss verdict itself: terminal loss boundary only; cursor/crafting **presence** proven for one identity plus one clear control; crafting close proven for one spare-inventory and one full-inventory close with no claim about other window types; no crash/power-loss, scale, concurrency or visual acceptance claim.
- Independent review of the current boundary changes has **not** been done.

**Historical evidence — earlier candidates only, labelled historical, not current proof.**
- *Historical:* restart persistence — an isolated Paper 1.21.11 / Java 21 fixture for an **earlier** candidate kept identity and history intact across a clean stop/restart.
- *Historical:* SQLite `integrity_check` returned `ok` on an **earlier** candidate's fixture database.
- *Historical:* that same earlier fixture passed startup, permissions, identity, history, read-only menu click and duplicate `NOTIFY` without item loss.
- *Historical:* the owner accepted the English command/GUI presentation in a real client on an earlier candidate. Vietnamese was not inspected and remains optional.
- None of these transfer to the current artifact. Each must be re-run, or explicitly rebound to the exact published artifact, before it may appear in the listing as current.

**Never verified at any point.**
- **Natural block-break publication** — issuing a tracked identity for an item obtained by breaking a block naturally. This remains open under risk `IG-R016`/successor-35 and must stay disclosed; do not let the smoke passes above imply it.
- **Crafting issuance** — only the fail-closed cancellation has been observed.
- Scale and concurrency, crash and power-loss recovery, other Paper versions.

## Artifact

- File name: `ItemGuard-LITE-1.0.0.jar` (LITE descriptor version string `1.0.0-lite`).
- Candidate SHA-256: `a952d16146082ffacfbcbf6032fdba0634c86e8099cbf6802685c2f76db71727`. This is the artifact the fixtures above ran on. Any rebuild replaces it and voids that binding.
- Release bundle: `ItemGuard-LITE-1.0.0.jar`, SHA-256 `(no archive - the JAR is uploaded on its own)26bf4fca2b194d43ba7de3f38933a2ec8671f42c20d5a204793c4868`, built and read back by `scripts/package_release_zip.py`, verdict `BUNDLE_MATCHES_CANDIDATE_OFFLINE_ONLY`. That verdict says the bundle carries the candidate above; it is offline packaging evidence only and proves nothing about runtime.
- Publish the JAR and ZIP SHA-256 values together, both read back from the final package, never typed from memory.

## Pre-publication checklist (owner)

- [x] *Historical:* owner accepted the English command/GUI presentation of an earlier candidate in isolated fixture `itemguard-lite-manual-78e03b378215` (2026-09-13). Vietnamese was not inspected and remains optional/pending; English is the release priority.
- [ ] **Runtime gate (OPEN):** re-run the runtime evidence on, or explicitly rebind it to, the exact artifact being published.
- [ ] Independent review of the current boundary changes.
- [ ] Owner approves this listing text and the support boundary.
- [ ] Final JAR and ZIP SHA-256 values, and the test count, are copied from the final release receipt into this document.
- [ ] Decide whether fail-closed craft cancellation is acceptable for a public free build, or gate it behind config first.
- [ ] Confirm the bStats disclosure above is acceptable, or add a per-plugin opt-out before release.
- [ ] Owner explicitly authorizes the upload. Nothing here authorizes publishing.
