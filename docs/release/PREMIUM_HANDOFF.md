# ItemGuard Premium — owner runbook and handoff

**Branch** `premium-mysql` · **artifact** `target/ItemGuard-1.0.0-shaded.jar` ·
SHA-256 `d17b8f81e7dccc02f035ab00255381a3de9770d7fe0c943ee5f82b0ec2432f0a` · 2026-09-19.

Written for the person who has to install, run, back up and roll back this plugin. Nothing here is a
plan: each claim maps to a file, a test or a receipt in `run/`. Where a boundary is unproven, it says
so in the same sentence as the capability.

---

## 1. What it is, and what "Premium" means here

ItemGuard tracks items by identity: a UUID written on the item, recorded in a database, with history
and observation, so staff can answer "where did this go" and, when the original is provably gone, hand
it back **without creating a second copy**. LITE (separate, frozen jar) observes and warns. Premium
adds writes: shared MySQL, the reclaim hand-over, the admin reports, scan metrics, Discord.

## 2. Install

1. Paper `1.21.11-131`, Java 21. (The 1.21.4+ floor has not been re-verified since schema v10.)
2. Drop the jar in `plugins/`. Start the server once; `plugins/ItemGuard/config.yml` and
   `messages_en.yml` are created.
3. Default backing store is SQLite — suitable for one server. For a network, configure MySQL (§4).
4. Grant permissions per §6. `/ig reload` reloads only the keys that are reload-safe; anything else
   logs "restart required" rather than appearing to take effect
   (`RestartSensitiveSettings`, `ReloadPolicy`).

## 3. The two switches that decide what this plugin may do

| Key | Ships as | What it gates |
|---|---|---|
| `anti-dupe.enabled` | `false` | Whether confirming duplicates runs at all. Detection is unit/MySQL tested; the end-to-end path has not been run on a controlled fixture yet, so it ships off. |
| `reclaim.issuance-enabled` | `false` | Whether `/matdo sos` and `/finditem giveoldid` may hand an item back. With it off, both refuse and record `ISSUANCE_DISABLED`. |
| `anti-dupe.action` | `NOTIFY` | Only `NOTIFY` can take effect: a destructive choice is downgraded and, since 2026-09-19, announced in the log at startup instead of being ignored silently. |
| `reclaim.external-absence-mode` | `STRICT` | How storage plugins that are **not installed** are treated when proving absence (§5). `STRICT` refuses every reclaim on a server that does not run PlayerVaults/zAuctionHouse; `INSTALLED_ONLY` skips a plugin that is not there, records the skip in the claim's evidence, and still refuses when an installed one cannot be read. |

## 4. MySQL (network / shared database)

Config block: `database.type: MYSQL`, `url` (`jdbc:mysql://host:3306/schema?...`), `user`,
`password`, `server-id`. Requirements the plugin enforces rather than assumes:

- The schema is initialized on first start and stamped with a version; a database whose recorded
  version is **newer** than the plugin refuses to start (fail-closed, by name).
- An existing schema that is **missing one of the plugin's tables is refused, not repaired** — a
  partial restore is not a fresh install (`MySqlSchemaManager.validateExistingSchema`).
- A restore into a second schema needs the plugin's database user **granted on that schema**;
  otherwise startup fails with an access error that looks like a plugin bug and is not.
- Destructive or identity-affecting writes never retry, buffer or replay when commit status is
  uncertain: the plugin fails closed and says so.

### Migrating a single-server install to MySQL

`/ig migrate` is one-way and dry-run first: it opens the SQLite file read-only, requires the supported
source schema, refuses a non-empty target instead of merging by guesswork, copies every data table,
stamps `server_id`, verifies per-table row counts and never writes to the source. Dump-only tooling
(`mysqldump`) must be used **without** `--databases`: that form writes the source `CREATE DATABASE`/`USE`
into the dump and a later restore silently writes back into the source schema.

### Backup and restore

Dump table statements only, into a second schema, grant the plugin user there, point a test server at
it, and read one identity back. A restore missing a table is refused by name. A restore that loses
**rows inside** existing tables cannot be detected by the plugin — it validates tables and version, not
content — so take the dump from a quiesced database.

## 5. The reclaim hand-over (the feature an owner will be asked about)

    arm      PENDING  -> PREPARED     identity locked (claim table unique index)
    deliver  one stack into the player's inventory (server thread, empty slot checked first)
    settle   PREPARED -> COMMITTED    delivered, permanently
             PREPARED -> DENIED       not delivered (full inventory, offline, unreadable snapshot), retryable

Player path: `/matdo check` lists eligible items; `/matdo sos <id>` proves absence (own inventory,
ender chest, PlayerVaults, zAuctionHouse — an adapter that is unavailable **denies**, it does not count
as absent) and then issues. Read §3 on `reclaim.external-absence-mode` before enabling issuance: with
the shipped `STRICT` value a server that does not run those two plugins will refuse every reclaim,
because the plugin cannot prove those storages empty — the honest reading of "refuse when absence is
not proven", but it means the switch alone is not enough on a normal server. `INSTALLED_ONLY` is the
setting for a server that keeps no items there, and every skipped source is named in the claim. Admin path: `/finditem giveoldid <id>` does the same for the recorded
owner, under its own permission, and proves absence before reserving anything.

The one outcome that needs a human: the item was delivered but the commit write failed. The player is
told, the claim stays `PREPARED` (so no later command can hand out a second copy), and the log carries
the claim id. Full table of failure outcomes: `docs/design/2026-09-19-premium-reclaim-issuance.md`.

**Not implemented, do not advertise:** per-player cooldown/quota, `addbackitem`/`removebackitem`/
`showbackitem`, `givenewid`, quarantine or any destructive/seizing action, the player-facing reclaim
GUI, and teleport-to-container (its permission has been deleted rather than left promising).

## 6. Permissions

| Node | Default | Meaning |
|---|---|---|
| `itemguard.check` | true | `/igcheck` (item in hand) |
| `itemguard.history` | true | own history |
| `itemguard.history.others` | op | another player's history, or a direct item code |
| `itemguard.search` | op | `/igsearch <player>` |
| `itemguard.stats` | op | `/igstats` |
| `itemguard.gui` | op | history GUI |
| `itemguard.notify` | op | **receive duplicate alerts in chat** (this is the alert node; it is the only one) |
| `itemguard.finditem.admin` | op | the `/finditem` family |
| `itemguard.giveoldid` | op | `/finditem giveoldid` — hands an item out, separate on purpose |
| `itemguard.matdo` | true | `/matdo check`, `/matdo sos` |
| `itemguard.reload` | op | `/ig reload` |
| `itemguard.migrate` | op | `/ig migrate` |
| `itemguard.track` | op | tracking when WorldGuard support is on |

`itemguard.*` grants all of the above. Deleted nodes: `itemguard.restore`, `itemguard.teleport` (the
first is covered by `matdo` + `giveoldid`, the second never existed) and `itemguard.bypass` (nothing
checked it once the alert bug was fixed).

## 7. Secrets

Never commit, and never put in a listing, screenshot or support pastie: database credentials, the
Discord webhook URL (it is a bearer credential — anyone with it can post as the bot), `server-id`
values that identify a customer's network, database dumps, `plugins/` runtime data, or the disposable
fixture directories (`tools/mysql-runtime/server`, `30_KET_QUA_THU_NGHIEM/`, `run/*.json` receipts are
safe: they contain counts, hashes and verdicts, and no credentials). The repository's gates check the
shipped surface, not your server's files.

## 7b. Running the gates yourself (and the disk)

Each runtime gate stages a full Paper server plus a copy of the Paper cache under
`E:/AI.WORK/30_KET_QUA_THU_NGHIEM/itemguard-*`, so a root is a few hundred megabytes and a full chain
adds several gigabytes. When the drive fills, Paper fails with
`OSError: [WinError 112] There is not enough space on the disk` **after** the gate has spent twenty
minutes starting servers, which reads like a product failure and is not one. Two habits prevent that:

- `python tools/premium-runtime/trim_fixture_roots.py` keeps every receipt, log and `stage.json` and
  removes only the bulk (`world*`, `libraries`, `versions`, `cache`, staged jars). It reports what it
  freed. Run it before a chain, or when free space drops below a few gigabytes.
- `python tools/premium-runtime/premium_reclaim_smoke.py` refuses to start below 4 GB free and says
  which script to run; the other gates do not check yet, so the trim script is the general remedy.

## 8. Evidence map for this artifact

| Claim | Evidence |
|---|---|
| Offline suite | `mvnw.cmd -o test` → 952/952 |
| MySQL schema, including the v9→v10 upgrade | `python scripts/run_mysql_schema_gate.py` → 43/43 across 11 tagged classes |
| Shipped text has no stray Vietnamese on an English surface | `python scripts/check_no_hardcoded_vietnamese.py` → 0; self-test 25/25 |
| Tooling contracts | `python -m unittest discover -s scripts` → 70/70 |
| Paper runtime on this hash | the seven gates in §9, receipts in `run/`, all bound to the hash named at the top of this document (artifacts change as fixes land; the ledger's CURRENT section always carries the current hash and receipt names) |
| The hand-over itself | `premium_reclaim_smoke.py`: refusal while held, issuance after `/clear` with the item back in a real client's inventory, the permanent claim lock, and the same lock after a restart |
| Ledger | `CURRENT_STATE.md` (CURRENT section) names the hash, the counts and every open boundary |

## 9. Before publishing (owner actions, in order)

1. Read §3 and decide whether the two switches ship off (they do today) or on after their runtime
   gates land.
2. Read the "Before FULL can be listed anywhere" section of `docs/release/LITE_VS_FULL.md` and decide
   which remaining requirement lines are in the first paid release.
3. Write the FULL listing from that truth table — not from the roadmap.
4. Upload the jar, tag the release and write the release notes yourself; this repository has never
   done that for Premium, and the artifact is not committed.
