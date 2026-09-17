# ItemGuard Premium — MySQL and cross-server identity: design contract

**Status:** design for approval. No code has been written against this document. Nothing here
is evidence of behaviour. Every code claim below was read from the current source tree and is
cited by `file:line`.

**Why this document exists.** `docs/release/TEST_PLAN_AND_PREMIUM.md` calls MySQL "the right
first feature" because a per-server SQLite file makes ItemGuard unusable for any network
running more than one server — an item moving between servers looks like two items. That
reasoning is sound. What is *not* sound is the same document's cost estimate: it says the port
is tractable because "only 6 files touch `Connection`/`PreparedStatement`, and there are 6 uses
of SQLite-specific SQL (`AUTOINCREMENT`, `INSERT OR REPLACE`, `last_insert_rowid`)". Section 2
of this document shows that estimate is wrong in both directions, and it is wrong in a way
that would have been discovered halfway through implementation.

Thanh's decisions, made 2026-09-16, are locked in section 3.

---

## 1. What we are actually building

Not "the same plugin on another driver". A shared database changes what the product *is*:

| | Single-server SQLite (today) | Shared MySQL (this contract) |
|---|---|---|
| Writer | exactly one, enforced by a sidecar OS lock | many servers, no lock the plugin can take |
| Same identity in two places | always a duplication *inside* one world | may be a legitimate cross-server move |
| Durability | plugin sets `PRAGMA synchronous = FULL` | `innodb_flush_log_at_trx_commit` is **server config the plugin cannot set** |
| Adoption | default, single server | upgrade path from existing SQLite history |

The second row is the headline feature and the hardest correctness problem: with a shared
database, "this identity exists twice" stops being a verdict and becomes a question —
*on which servers, and did it move or was it copied?*

---

## 2. The real port surface (evidence, not estimate)

### 2.1 Correction of the existing estimate

| Claim in `TEST_PLAN_AND_PREMIUM.md:102` | Reality in this tree |
|---|---|
| "6 files touch `Connection`/`PreparedStatement`" | 8 files import `java.sql` (`CatalogIndexSchema`, `CatalogRepository`, `CatalogText`, `ItemSqliteRepository`, `SqliteConnectionOwner`, `SqliteLossJournal`, `SqliteSchemaManager`, `DiscordWebhook`) — closer to right than wrong |
| "6 uses of SQLite-specific SQL … `AUTOINCREMENT`, `INSERT OR REPLACE`, `last_insert_rowid`" | `AUTOINCREMENT` ×3 — real. **`INSERT OR REPLACE`: zero occurrences repo-wide. `last_insert_rowid`: zero occurrences repo-wide.** Both were listed and neither exists |
| "`SqliteConnectionOwner.java:243` is the single place a connection is opened" | True — confirmed, `openConnection()` at 242–261 |
| implied: the port is a driver swap | False. The schema depends on **two partial unique indexes**, an **FTS5 trigram virtual table**, and **SQL triggers that call a Java UDF**. MySQL supports none of the three |

The estimate over-counted tokens that do not exist and under-counted the constructs that
actually block. A port planned from it would have started at the driver layer and hit FTS5
after the schema work was already committed.

### 2.2 Load-bearing constructs with no MySQL equivalent

**a. Partial unique indexes — two, both invariants.**

```
SqliteSchemaManager.java:142  CREATE UNIQUE INDEX idx_reclaim_identity_lock
                              ON reclaim_claims(player_uuid, code)
                              WHERE state IN ('PENDING','PREPARED','COMMITTED')
SqliteSchemaManager.java:174  CREATE UNIQUE INDEX idx_tag_publication_source_lock
                              ON tag_publications(source_key)
                              WHERE state = 'PREPARED'
```

MySQL has no partial indexes at all. These are not optimisations: the first is what stops two
active reclaim claims for one identity; the second is what stops two in-flight publications for
one physical source — the mechanism the entire first-tag path relies on. They must be
re-expressed, not translated.

**b. FTS5 with a trigram tokenizer.**

```
CatalogIndexSchema.java:45   CREATE VIRTUAL TABLE catalog_search_fts
                             USING fts5(code,name,material,
                             tokenize='trigram case_sensitive 1')
```

This backs `/ig browser` search. The nearest MySQL facility is InnoDB `FULLTEXT` with
`WITH PARSER ngram`, which is **not** an equivalent: no `case_sensitive` option, different
minimum token length, and different matching semantics for the substring queries the catalog
issues. This is a behavioural change to a shipped LITE feature, so it cannot be hidden inside
a "support MySQL" task.

**c. Triggers calling a Java UDF.**

```
CatalogText.java:9           org.sqlite.Function.create(connection,
                             "ig_catalog_index_fold_v1", …, FLAG_DETERMINISTIC)
CatalogIndexSchema.java:23,55,64   triggers call ig_catalog_index_fold_v1(...)
```

The catalog's index-maintenance triggers call a function implemented in Java inside the
plugin process. MySQL cannot host that: a UDF lives on the server and cannot be the plugin's
code. The fold (NFC + lowercase) has to move to write-time in the application, with the
triggers replaced by explicit writes or generated columns.

**d. Driver-specific durability and budget.**

```
SqliteConnectionOwner.java:245,256,257   PRAGMA foreign_keys / synchronous / journal_mode
SqliteSchemaManager.java:215–225         connection.unwrap(org.sqlite.SQLiteConnection.class)
                                         + org.sqlite.ProgressHandler (index-build budget)
SqliteSchemaManager.java:251,262         PRAGMA index_list / index_xinfo (schema validation)
```

Two consequences that are contract-level, not cosmetic:

- **Durability stops being ours to state.** Today the plugin decides
  `PRAGMA synchronous = FULL`. The MySQL equivalent is
  `innodb_flush_log_at_trx_commit = 1`, which is read *from the server*. A plugin that cannot
  verify it is back to guessing about the last transaction — the exact failure mode the comment
  at `SqliteConnectionOwner.java:246–251` exists to prevent. Under MySQL the plugin must
  **read and verify** this, and refuse to start when it cannot be established.
- **Schema validation has no `PRAGMA`.** `index_list`/`index_xinfo` checks become
  `information_schema` queries with their own compatibility surface.

---

## 3. Decisions locked (Thanh, 2026-09-16)

**D1 — Server identity is a column, not a join.** Add `server_id` to the observation, history
and publication rows — the rows that record *where an item was seen* — which is enough to
answer "this item is on server A and server B" without joining a `servers` table. No
`servers` table; no rename/alias support in v1.

**D2 — One writer per identity row, arbitrated by the database.** `SELECT … FOR UPDATE` inside
a transaction, taking the identity row (`tracked_items.code`) as the lock. The existing
single-writer mental model is preserved; SQLite's freebie is replaced by an explicit lock
rather than by optimistic retries. Note this makes transaction scope a correctness concern:
the lock must be held for exactly the window the old serial executor gave us for free.

**D3 — Migration is a one-way command.** `/ig migrate` with a dry-run first, copying rows with
`code` and `item_uuid` preserved unchanged, verifying row counts at the end, and **never
deleting the SQLite file**. No reverse path.

### Defaults this document proposes (not yet approved)

**D4 — Connection loss fails closed, with no write buffer.** A mid-write network failure
denies the operation and does not mint an identity it cannot prove was written. Writes are
never queued to local disk for later replay — a replay buffer is a new duplication source, and
the plugin's whole value is that it does not create those.

**D5 — The single-owner sidecar lock becomes backend-conditional.** `IG-R005`'s guarantee
("exactly one cooperating owner") exists because two plugin instances must not share a SQLite
file. Under MySQL, several servers *are* meant to share the database, so the sidecar lock must
apply to SQLite only. Leaving it unconditional would fail-closed every legitimate multi-server
setup; removing it unconditionally would silently drop a verified guarantee for SQLite users.

**D6 — Backend selection stays an explicit, fail-closed config switch.** `DatabaseBackendPolicy`
already rejects anything but `SQLITE` at `DatabaseManager.java:50`. `MYSQL` is added as a real
value; unknown values keep failing startup with the same message. The existing test that
asserts `MYSQL` is rejected is *replaced by a deliberate edit*, not deleted silently.

---

## 4. Cross-server duplicate detection — the one rule that must not be copied from LITE

Today a duplicate is confirmed from **two exact observations inside one completed
`scan_epoch`**, invariantly protected by
`UNIQUE(scan_epoch, holder_type, holder_id, slot, item_uuid)` (`SqliteSchemaManager.java:73`).
Scan epochs are minted per process, so two servers never share one.

Therefore cross-server detection **cannot** reuse the epoch rule. The proposed contract:

- A cross-server finding requires the same `item_uuid` observed with **two different
  `server_id` values** within a configured window, both observations being unexpired.
- It reports **which servers**, and it does **not** claim duplication — the honest wording is
  "seen on two servers", because a legitimate move and a copy are indistinguishable from
  observation records alone.
- A same-server duplicate continues to use the existing epoch rule unchanged. The two rule
  sets stay separate so that adding the cross-server case cannot weaken the single-server
  guarantee that has runtime evidence behind it.

This wording matters commercially as much as technically: the listing may not promise a
cross-server dupe *verdict* that the data cannot support.

---

## 5. Work sequence

Each step ships behind the explicit config switch, so nothing reaches a LITE user until the
whole feature is verified.

| | Step | Gate to move on |
|---|---|---|
| M0 | Backend seam — **landed 2026-09-16**: `DatabaseBackend.MYSQL`; `requireSupported` accepts it; new `requireImplemented(DatabaseBackend)` refuses it by name with an actionable reason (exhaustive enum switch, no `default`, so a backend added without a decision is a compile error); wired at `DatabaseManager`. Full suite **810/810 PASS** (+4 tests). *Not* in M0, deliberately: MySQL config keys and the backend-conditional sidecar lock (D5) — neither is reachable while no MySQL owner class exists, and D5 is satisfied by construction as long as MySQL does not reuse `SqliteConnectionOwner` | SQLite behaviour unchanged: 810/810 green, no SQLite code path touched |
| M1 | Portable schema: replace the two partial indexes (2.2a), translate DDL, `information_schema` validation | Same invariants proven by test on both backends |
| M2 | Concurrency: `SELECT … FOR UPDATE` per identity (D2); connection-loss fail-closed (D4) | Contention tests: two writers, one identity, no lost or double write |
| M3 | `server_id` (D1) + cross-server finding rule (section 4) | Finding names both servers; same-server rule unchanged |
| M4 | Catalog search on MySQL (2.2b/2.2c) — **its own contract**, since it changes shipped LITE search behaviour | Search results equivalent on both backends |
| M5 | `/ig migrate` (D3) | Dry-run, count verify, source DB untouched |
| M6 | Two-server runtime fixture | See below |

M4 is deliberately not smuggled into M1: it changes what a *shipped* command returns, which is
a different decision with a different owner.

---

## 6. The evidence this feature needs before it may be called working

Per the project's evidence ladder, and matching the fixture shape the premium plan already
calls for:

- **`VERIFIED` requires runtime, not unit:** two Paper servers against one MySQL instance, a
  tracked item physically moved between them, and a cross-server finding recorded — plus a
  same-server duplicate still reported by the old rule, and one PostgreSQL-free negative: with
  MySQL unreachable mid-operation, no identity is minted and no operation half-succeeds.
- **Durability claim:** the plugin either verifies `innodb_flush_log_at_trx_commit = 1` and
  says so, or it refuses to start. It never inherits it silently.
- **Migration:** `/ig migrate` on a real SQLite database with real history, verified by
  row-count equality per table and by reading the migrated rows back.
- **Not claimed by this contract:** multi-writer SQLite, shared/network filesystems, and any
  production deployment. Those stay `NOT VERIFIED`, exactly as `IG-R001`/`IG-R005` state today.

---

## 7. Open questions — answered 2026-09-16

**1. `server_id` value — decided: an admin-configured name, with a generated value when blank.**
Readable in findings ("survival-1"), which is the point; a rename is a deliberate config edit and
does not need to be survivable by design in v1.

**2. Catalog search on MySQL (M4) — decided: the two backends are allowed to differ, and the
listing says so.** SQLite continues to use FTS5/trigram; MySQL uses InnoDB `FULLTEXT` with the
`ngram` parser. The listing must state that search behaviour differs per backend rather than
imply equivalence. This is the same rule the project already applies to platform support:
narrower and stated beats wide and assumed.

**3. Migration scope — decided: `/ig migrate` ships in the first Premium release.** The original
reason for choosing migration at all — "a Premium upgrade that silently starts from zero would be
a refund" — only holds if it is available on day one.

No open questions remain in this document. Implementation may start at M0.

---

## 8. M1 landed 2026-09-18 — and four traps the estimate did not have

`MySqlSchemaManager` (`src/main/java/com/itemguard/persistence/`) creates the same nine tables at
the same schema version, with the two partial unique indexes re-expressed as `STORED` generated
columns behind plain unique keys:

| SQLite | MySQL | Why it is equivalent |
|---|---|---|
| `UNIQUE INDEX idx_reclaim_identity_lock ON reclaim_claims(player_uuid, code) WHERE state IN ('PENDING','PREPARED','COMMITTED')` | `active_identity_lock` = `CASE WHEN state IN (…) THEN CONCAT(player_uuid, ':', code) END`, `STORED`, `UNIQUE KEY` | MySQL does not constrain `NULL`, so only blocking states occupy the key — the same selectivity as the partial index |
| `UNIQUE INDEX idx_tag_publication_source_lock ON tag_publications(source_key) WHERE state = 'PREPARED'` | `prepared_source_lock` = `CASE WHEN state = 'PREPARED' THEN source_key END`, `STORED`, `UNIQUE KEY` | same |

**Four behaviours were found that section 2.2 did not list, and each one would have been a
runtime defect rather than a compile error:**

1. **Collation is an identity rule, not decoration.** MySQL's default `utf8mb4` collation is
   case- and accent-insensitive, so on the default the codes `AB12CD` and `ab12cd` are *one*
   identity, and `utf8mb4_bin` (PAD SPACE) would still make `'AB12CD '` equal `'AB12CD'`. Every
   table is created `COLLATE utf8mb4_0900_bin` — byte-wise and NO PAD, the comparison SQLite's
   `BINARY` performs. Pinned by a test that inserts three such identities and by a parity test
   that counts the collation declarations in the DDL.
2. **`BLOB` caps at 65,535 bytes; the snapshot codec accepts 1 MiB.** A snapshot between the two
   is stored by SQLite and rejected — or truncated under a relaxed `sql_mode` — by MySQL.
   Payload columns are `MEDIUMBLOB`; a test stores a 100 KB payload and reads its length back.
3. **`CREATE INDEX IF NOT EXISTS` is MariaDB syntax.** MySQL rejects it, so the history index is
   looked up in `information_schema.statistics` first. (The parity test asserts the SQLite-only
   syntax is absent — against source with comments stripped, because a comment mentioning a
   forbidden construct is not the construct.)
4. **Durability and strictness are the server's, and both are now read.** `PRAGMA synchronous =
   FULL` becomes a read of `innodb_flush_log_at_trx_commit`, which is **global-only** in 8.4 —
   `SET SESSION` is rejected, measured. The plugin therefore cannot make the read
   self-fulfilling; it refuses to start when the value is not `1`. `sql_mode` is checked for
   strictness (`STRICT_ALL_TABLES` or `STRICT_TRANS_TABLES`; MySQL 8.4 ships the latter) and for
   `NO_ENGINE_SUBSTITUTION`, because a non-strict mode truncates instead of rejecting. A server
   that reports itself as MariaDB, or a major version below 8, is refused by name.

**Two guarantees deliberately do not carry over**, and are stated in the class javadoc rather
than implied: the history-index build budget (SQLite's `ProgressHandler` has no MySQL analogue —
a client cannot interrupt its own DDL) and rollback of a failed initialization (MySQL DDL commits
implicitly, so re-running must be, and is, safe instead).

**Not covered by M1 — unchanged and still open:** M2 (`SELECT … FOR UPDATE` per identity,
connection-loss fail-closed), M3 (`server_id` and the cross-server finding), M4 (catalog search),
M5 (`/ig migrate`), M6 (two-server runtime fixture). `requireImplemented(MYSQL)` still refuses the
backend, so none of this is reachable by an admin yet — the M0 seam working exactly as intended.

### Evidence

    python scripts/run_mysql_schema_gate.py
        -> run/mysql-schema-gate-20260918-030349.json
           PASS_MYSQL_SCHEMA_INVARIANTS · MySQL 8.4.6 · 10/10 tests · fixture stopped,
           port closed, process gone

    mvnw.cmd -o test              -> 868/868, 0 failures/errors/skipped   (mysql tag excluded)
    mvnw.cmd -o -Pmysql test      -> only the tagged class, and the profile fails when none ran

The MySQL tests need the fixture, so they are excluded from the default build by tag and selected
by `-Pmysql`; the profile sets `failIfNoTests`, because a tag filter that matches nothing must not
look like a passing gate. `MySqlSchemaParityTest` (6 tests, no server needed) and
`MySqlSchemaSessionGuardTest` (7 tests, stubbed connection) keep the invariants and the refusal
paths covered in the default build.

**No LITE change.** The three copies of the 1.0.0 candidate still hash `8c0e540e…` after every
run in this session; the MySQL code is additive, `mysql-connector-j` is `test` scope, and the
work is on the `premium-mysql` branch so `main` remains the release commit.
