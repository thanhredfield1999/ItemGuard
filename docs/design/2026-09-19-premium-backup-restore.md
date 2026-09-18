# Premium: backup, restore and what the plugin refuses

Status: controlled evidence on artifact `d1aac80a…` (Paper `1.21.11-131`, MySQL 8.4.6 fixture).
Receipts: `run/premium-backup-restore-20260919-014648.json` (current), and
`run/premium-backup-restore-20260919-000642-pre-fix.json` (the same procedure on the previous
artifact, which is where the partial-restore hole was measured).

This is the operational half of `docs/design/2026-09-16-premium-mysql-contract.md`. It records the
procedure that was actually run on a disposable fixture, the guarantees that procedure produced, and
the boundaries it does **not** cover.

## Procedure that was measured

1. Stop Paper. The dump here was taken with the server down, so the backup is a consistent point in
   time by construction.
2. Dump the schema:

   ```
   mysqldump --protocol=tcp -h <host> -P <port> -u <user> --routines --events \
       --single-transaction <database> > itemguard.sql
   ```

   **Without `--databases`.** That form writes `CREATE DATABASE`/`USE` for the source schema, so
   loading the dump into a restore schema silently writes back into the source instead. The
   controlled runner refuses a dump that carries database-selection statements for exactly that
   reason.
3. Create the target schema and load the dump:

   ```sql
   CREATE DATABASE <restore> CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
   ```

   then `mysql ... <restore> < itemguard.sql`, and **grant the plugin's database user rights on the
   restored schema**. Measured: a fixture user granted only on the seeded schema makes ItemGuard
   fail to open the restored one at startup. That step belongs to the operator, not to the plugin.
4. Start Paper. ItemGuard validates the recorded schema version and the presence of every table it
   owns *before* running any DDL.

## What is guaranteed on this artifact

| Case | Result (receipt phase) |
|---|---|
| Full restore into a second schema | per-table row counts identical to the source (`tracked_items` 1, `item_history` 4, `item_snapshots` 1, `tag_publications` 2, `plugin_stats` 1), schema version 9 |
| Plugin starts on the restored schema | enables, and a real client reads the same identity back: `/ig check` returns the same code, `/ig stats` reads MYSQL, and the server-side history for that code still lists `PICKUP`, `DROP`, `INVENTORY_MOVE`, `SPAWN` (phase B3) |
| Restore that is **missing a data table** | refused by name — `Refusing to start: … is missing item_history …` — and the missing table is **not** re-created (phase C, after the fix) |
| Recorded schema version newer than this build | refused by name: `Unsupported future ItemGuard schema version: 99` (phase D) |
| A database with no ItemGuard schema at all | created from scratch, version 9 seeded (test `MySqlPartialRestoreRefusalTest.emptyDatabaseIsInitialized`) |

Phase C is the case that used to pass silently. On the previous artifact the same fixture produced
`SILENT_RECREATE_EMPTY_HISTORY`: the missing table was re-created empty, the plugin enabled, and
nothing was logged. The item identity survived while its history did not — the plugin looked healthy
on a half-restored database. `MySqlSchemaManager.validateExistingSchema` now refuses that state, and
the MySQL-tagged test `MySqlPartialRestoreRefusalTest` pins both halves (refuse an incomplete
existing schema, still create a fresh one).

## Boundaries this evidence does not cover

- **Missing rows inside tables that do exist.** A dump taken earlier, or one that lost rows, is
  indistinguishable from ordinary data to the plugin: it validates tables and version, not content.
  Row counts and checksums kept by the operator are what detects that.
- **Restoring into a schema another server is writing to.** Both servers would need the same
  `multi-server.server-id` rules and the restore still replaces rows underneath a live writer; the
  verified procedure stops the server first.
- **The dump tool's own durability.** The plugin requires `innodb_flush_log_at_trx_commit = 1` and
  refuses a server that does not state it, but it makes no claim about a particular backup product
  or about the integrity of a backup file on disk.
- **Production scale.** The fixture holds a handful of rows. A large `item_history` restore is
  bounded by MySQL, not by anything measured here.
