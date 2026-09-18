# LITE vs FULL — what the code actually does today

**Re-audited from source on 2026-09-19**, replacing the 2026-09-15 audit. Every row names the code
that makes it true. This document exists because a paid listing that advertises a capability the code
does not have is the most damaging thing this project could ship — and the 2026-09-15 audit found
exactly five of those in FULL (the previous version of this file is in git history).

---

## The short version

**LITE is honest, as before.** Everything its listing claims, it does.

**FULL is closer but still not listable**, and the gap is smaller and different from before: the two
permissions that advertised nothing (`itemguard.restore`, `itemguard.teleport`) are **gone from
`plugin.yml`**, Discord is wired, and reclaim issuance now exists behind `reclaim.issuance-enabled`
(off by default). What still blocks a listing: no Paper runtime journey has exercised issuance, the
duplicate-detection path has never been run end-to-end on a controlled fixture, and several
requirement lines (cooldown/quota, quarantine, give-back queue, the player reclaim GUI) remain
unimplemented. Those are listed at the bottom, in the owner's language.

---

## Capability table

| Capability | LITE | FULL | Proof |
|---|---|---|---|
| Commands | `/ig` only | `/ig`, `/igcheck`, `/ighistory`, `/igsearch`, `/igstats`, `/finditem`, `/matdo` | `lite/plugin.yml` vs `plugin.yml` |
| Duplicate action | forced `NOTIFY` | reads config, but every destructive choice is downgraded | `ConfigManager.java` `isAntiDupeEnabled`, `AntiDupeActionPolicy.resolve(…, false)` |
| That downgrade, when chosen | n/a | **now announced at startup** | `DestructiveAntiDupeNotice`, called from `ItemGuard.warnAboutIgnoredAntiDupeAction` |
| Restore / reclaim | absent | **implemented behind a gate** — arm → deliver → settle, `COMMITTED` forever | `ReclaimIssuanceService`, `ReclaimIssuanceFlow`, `MatDoCommand.runIssuancePhase`, `FindItemCommand.giveOldId` |
| Issuance switch | n/a | `reclaim.issuance-enabled`, ships `false`, LITE off regardless | `ConfigManager.isReclaimIssuanceEnabled`, `config.yml` |
| Proving absence outside the player | n/a | `reclaim.external-absence-mode`: `STRICT` (ships) refuses when a storage plugin is not installed; `INSTALLED_ONLY` skips absent plugins and records the skip, still refusing for installed-but-unreadable ones | `ExternalAbsenceMode`, `ExternalPresenceProbeFactory`, `ExternalAbsenceModeTest` |
| Teleport to a container | absent | **absent, and the permission is deleted** | no `itemguard.teleport` in `plugin.yml`; `PermissionDeclarationContractTest` |
| `itemguard.restore` | absent | **deleted** — the reclaim path uses `itemguard.matdo` and `itemguard.giveoldid` | same test |
| `itemguard.bypass` | absent | **deleted** — its only reader was the duplicate-alert gate, which was the bug | `InventoryScanTask.reportFindings`, same test |
| WorldGuard | forced off | works — gates tracking on a region flag | `ItemTrackingService` |
| Discord | forced off | **works** — a confirmed finding sends the embed, on `discord.enabled` | `DiscordWebhook.sendDuplicateFinding`, called from `InventoryScanTask.reportFindings` |
| History cleanup | forced off (keeps everything) | `performance.auto-cleanup` — the switch is now the switch, ships `false` | `ConfigManager.isAutoCleanupEnabled`, `AutoCleanupContractTest` |
| GUI | small read-only menu | works — full catalog browser | `MainCommand`, `catalog/` |
| Live search | ID lookup only | works — `/finditem` across players and containers | `commands/FindItemCommand`, `catalog/` |
| Admin reports | absent | `infoitem`, `infoplayer`, `infodupe`, `readfinding`, `readdupe`, `checktps` | `FindItemReportTask`, `FindItemCommandParser` |
| Scan metrics | absent | p50/p95/last/avg of the scan, epochs, findings, alerts, skipped-busy scans | `ScanMetrics`, `/finditem checktps` |
| Finding acknowledgement | absent | MySQL only (schema v10); SQLite answers "needs the MySQL backend" | `ItemSqliteRepository.acknowledgeFindings`, `MySqlFindingAcknowledgementTest` |
| Shared database | SQLite only | MySQL, schema v10, `/ig migrate` one-way with per-table counts | `MySqlSchemaManager`, `MySqlMigrationService`, migration gate |
| Backup/restore | n/a | dump/restore gate, partial restore refused by name | `docs/design/2026-09-19-premium-backup-restore.md` |
| Command names | `/ig` | `/finditem` (alias `itemsearch`), `/matdo` (alias `itemreturn`) | `plugin.yml` |

---

## What changed since the 2026-09-15 audit

1. **Restore/reclaim moved from "pipeline with no success branch" to "implemented, gated, unproven at
   runtime".** The order of the hand-over is the guarantee (arm locks the identity, then the inventory
   write, then commit-or-deny); the design doc states each failure's outcome and what is still missing.
2. **Discord is wired**, and the unused `sendDuplicateAlert(itemName, code, holderName, location)` was
   replaced by `sendDuplicateFinding(code, itemUuid, distinctLocations, scanEpoch)` — nothing called
   the old one, and detection cannot supply a holder or a location, because that is the question the
   alert is asking.
3. **The two dead permissions are gone**, and a test now fails if any declared permission has no Java
   literal behind it (`PermissionDeclarationContractTest`, with a three-entry, reasoned allow-list).
   Running that test is what turned up `itemguard.bypass` as a third case: with the alert gate fixed,
   nothing read it, so it went the same way.
4. **`anti-dupe.action` stops being silent.** A destructive choice logs one warning naming the
   effective action and how to silence it.
5. **The duplicate-alert permission bug is closed**: alerts checked `itemguard.bypass` on FULL and
   `itemguard.notify` on LITE, and `notify` was not declared at all.

---

## Before FULL can be listed anywhere

1. **Run the runtime gates for issuance and for duplicate detection** on the release artifact: give →
   snapshot → prove absence → issue → restart → read back, an inventory-full retry, and a fixture with
   two real copies of one identity producing one finding and one alert. Until those exist, the two
   headline features are proven only offline.
2. **Decide the release shape with the owner**: whether `reclaim.issuance-enabled` ships `false` with
   the listing saying "enable after you have read the runbook" (current state), or the gate's runtime
   evidence lands first and it ships `true`. The second decision that travels with it is
   `reclaim.external-absence-mode`: on a server without PlayerVaults/zAuctionHouse, `STRICT` (the
   shipped value) means no reclaim can ever be issued, so a listing that advertises "get your item
   back" has to say which setting it assumes.
3. **Decide `anti-dupe.action`**: keep the downgrade and the new warning, or design the audited
   destructive path (audit record with actor/reason/evidence/result per `PRODUCT_REQUIREMENTS.md:25`).
4. **Requirements still unimplemented**: per-player reclaim cooldown/quota,
   `addbackitem`/`removebackitem`/`showbackitem`, `givenewid`, quarantine/RESOLVED, the player-facing
   reclaim GUI, and the transfer restrictions in the requirements' "Hạn chế chuyển vật phẩm" section.
5. **Listing material for FULL does not exist yet**: no FULL README/licence/package script, no listing
   draft, no pricing. LITE's equivalents are in `docs/release/` and can be used as the shape.
6. **Compatibility**: FULL's MySQL path is exercised on Paper `1.21.11-131` with Java 21; the 1.21.4+
   floor has not been re-run since the schema moved to v10.

None of this blocks LITE. LITE's shipped jar is built from `main` and its claims were audited
separately and still hold.
