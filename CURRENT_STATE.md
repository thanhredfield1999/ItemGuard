# ItemGuard — Current State

## CURRENT — Branch `premium-mysql` — 2026-09-18 — Premium wiring + async P0 slice verified offline/fixture

This branch keeps the Premium candidate rebuildable on top of `main` release commit `6292638`.
The frozen LITE candidate on `main` is not rebuilt or changed by Premium work.

On `premium-mysql`, `DatabaseManager` explicitly selects SQLite or MySQL. MySQL fails closed during
construction when configuration, durability, strictness, schema, or server identity is unacceptable.
The P0 async slice now removes DB waits from `CheckCommand`, scheduled history cleanup, and
`InventoryScanTask` construction; scan startup remains fail-closed until the persisted epoch floor
has loaded. This is source-level/fixture evidence, not Paper-runtime or production verification.

Fresh evidence bound to the current source tree:

    mvnw.cmd -o test                                      890/890, 0 failures/errors/skipped
    python scripts/run_mysql_schema_gate.py               35/35, 0 failures/errors/skipped
                                                            MySQL 8.4.6; expected tagged classes
                                                            present; fixture stopped, port closed,
                                                            process gone
    mvnw.cmd -o -DskipTests package                       BUILD SUCCESS
    target/ItemGuard-1.0.0-shaded.jar                     SHA-256 8dfcda67c091dc4f65b9e126276cffab7c7ab1ab29e4383e62dac492d27023eb
                                                            1,267 relocated Connector/J/Hikari/SLF4J
                                                            entries

MySQL gate evidence: `run/mysql-schema-gate-20260918-171956.json`.
Async boundary evidence includes RED→GREEN contract tests for `CheckCommand`, cleanup, persisted
epoch initialization, and the async epoch-floor behavior. Existing History/Search/Stats/Lite/GUI
read paths remain async; sync public APIs remain compatibility surfaces and are not claimed safe for
arbitrary external callers.

Runtime-wiring coverage includes canonical item and snapshot upsert, history with `server_id`,
observation upsert, epoch audit, search request, reclaim idempotency, tag publication, loss journal
atomic write, and server identity on a real fixture. MySQL identity-affecting writes use `FOR UPDATE`;
SQLite keeps its serialized executor path.

Open evidence boundaries: no controlled Paper startup with `database.type: MYSQL`; no two Paper
servers; no production deployment. Cross-server proof remains the database-level two-owner fixture.
IG-R022 remains partially mitigated until the remaining compatibility/GUI boundaries receive Paper
proof. The Premium jar is not uploaded, tagged, or released.

The detailed M1–M6 narrative below is historical. The section above is the only current binding.

## HISTORY — Premium implementation narrative

M1 is the portable schema: `MySqlSchemaManager` creates the same nine tables at the same schema
version, with SQLite's two partial unique indexes re-expressed as `STORED` generated columns
behind plain unique keys, byte-wise (`utf8mb4_0900_bin`) comparison, and `MEDIUMBLOB` payloads
because MySQL's `BLOB` stops at 65,535 bytes while the snapshot codec accepts 1 MiB. Session
durability and strictness are read from the server and refused by name when they are not what the
plugin requires — `innodb_flush_log_at_trx_commit` is global-only in 8.4, so it cannot be set
per session. Full detail, including the four traps that were not in the original port estimate:
`docs/design/2026-09-16-premium-mysql-contract.md` §8.

Evidence, all run on 2026-09-18 against a controlled MySQL 8.4.6 fixture
(`tools/mysql-runtime/`, port 33316, started and stopped by the gate itself):

    run/mysql-schema-gate-20260918-033653.json   PASS_MYSQL_SCHEMA_INVARIANTS
                                                 29/29 tests across four classes (10 schema
                                                 invariants + 8 lock + 4 cross-server + 7 owner),
                                                 fixture stopped, port closed, process gone
    mvnw.cmd -o test                             884/884, 0 failures/errors/skipped
                                                 (the mysql tag is excluded; the parity, session
                                                 guard and multiserver tests are in this count)
    mvnw.cmd -o -Pmysql test                     only the tagged classes; the profile fails when
                                                 nothing ran

M2 is the concurrency primitive, `MySqlIdentityLock`: `SELECT … FOR UPDATE` on the identity row,
inside the caller's transaction, with the class itself owning the transaction so a caller cannot
take the lock and then write outside it. An identity that does not exist is refused; the wait is
bounded so a blocked writer fails visibly; there is no retry and no local write buffer. Proven by
a pair of tests — the same two-writer read-modify-write loses an update *without* the lock
(`detection_count = 1`) and keeps both updates *with* it (`= 2`) — plus lock-until-commit,
different-identities-don't-block, rollback-releases, and an aborted connection leaving nothing
visible. Detail: `docs/design/2026-09-16-premium-mysql-contract.md` §9.

M3 is server identity and the cross-server rule. The MySQL schema is now **version 9** — the
ladder moved, SQLite stayed at 8, and the parity test asserts the one-migration difference with
the reason, because adding the column to the shipped LITE schema would change a candidate whose
evidence is bound to its jar. `server_id` is on observations, history and publications, indexed
with the observation time. `CrossServerFindingPolicy` answers only "seen on N servers, named",
with a test that fails if any status or message says duplicate/dupe/copy/cloned — observations
cannot tell a move from a copy. The rule has its own vocabulary (`CrossServerSighting`) rather
than widening `ItemObservation`, so a change here cannot reach the epoch rule that has runtime
evidence. Config `multi-server` is inert on SQLite. Detail: §10.

M4 is implemented on this branch: `MySqlCatalogRepository` uses an InnoDB `FULLTEXT` index with
the MySQL `ngram` parser for non-empty catalog searches and keyset pagination for empty searches.
`MySqlSchemaManager` creates `idx_tracked_items_catalog_fulltext`; the test proves a real MySQL
8.4.6 fixture returns the expected catalog row. This is intentionally not claimed equivalent to
SQLite FTS5/trigram; the Premium listing must state backend-specific search semantics.

Fresh M4 evidence: `mvnw.cmd -o -Pmysql -Dtest=MySqlCatalogRepositoryTest test` = 1/1 PASS,
then `mvnw.cmd -o test` = 884/884 PASS, and `scripts/run_mysql_schema_gate.py` = 30/30,
`PASS_MYSQL_SCHEMA_INVARIANTS`; fixture stopped, port closed, process gone.

M5 is implemented: `MySqlMigrationService` opens SQLite read-only, requires source schema 8,
does dry-run first, refuses non-empty MySQL targets, copies all eight data tables, stamps
`server_id`, updates target metadata to MySQL schema 9, and verifies per-table counts. `/ig migrate`
is admin-only and dry-run by default; `/ig migrate confirm` is the explicit copy operation.
The runtime path now uses Connector/J `MysqlDataSource` behind HikariCP, with Maven relocation for
Connector/J, HikariCP and SLF4J; global `DriverManager` is not the shipping migration path.

Fresh M5 evidence: Hikari + migrate command contracts `2/2 PASS`; real MySQL migration fixture
`2/2 PASS`; compile/test-compile `BUILD SUCCESS`; fixture stopped, port closed, process gone.

Historical at the time of this entry: M6 and Paper runtime wiring were still open.
The current section above supersedes this entry. Database-level M6 evidence is now present; the
remaining boundary is controlled Paper startup with MySQL, a two-Paper-server fixture, and
production evidence. The D4 shape (fail closed, no buffer) is implemented.

Historical M2b entry: `MySqlConnectionOwner` supplied the bounded pool, session guard, schema install,
transaction ownership and fail-closed behavior. At that time the runtime wiring was not yet present;
the current section above supersedes that statement. The current branch wires the owner through
`DatabaseManager` and adds repository identity locking. Detail: §11.

## HISTORY — 2026-09-17 — a third review of this candidate's own code, adjudicated and answered

### 2026-09-18 — the public repository now mirrors this candidate; nothing was rebuilt

`main` was four commits behind reality: everything below — source, tests, gates, tooling and
documentation — sat uncommitted in the working tree. It is published now in two commits
(`78f833a` code + tooling + `run/` evidence, `6945f52` docs + this ledger) on
`github.com/thanhredfield1999/ItemGuard`, a **public** repository, at Thanh's instruction. The
reviews, the risk register and the premium design contracts go with it, open rows included.

No source file was touched for this, so the candidate is still `8c0e540e…` and everything bound to
it stays bound. Re-run in that same session, on that jar: **855/855 Java tests** (`mvnw -o test`,
JDK 21, BUILD SUCCESS), 67/67 script tests, 107/107 tooling contracts, 0 findings from both the
listing gate and the Vietnamese gate (source and jar), `ARTIFACT_CONSISTENT_OFFLINE_ONLY`.

Not published: the jar itself, `target/`, runtime fixtures, dev-server logs, the code index —
`.gitignore` now names them. No tag and no GitHub Release were created: the upload stays Thanh's
own step. The seven runtime gates and the nine-version matrix were **not** re-run; their records
are files in `run/`.

### 2026-09-17 (late) — review #3: 3 HIGH / 7 MEDIUM / 6 LOW, every verified finding fixed

A third read-only adversarial review (`docs/reviews/2026-09-17-review3-candidate-and-gates.md`) covered
what the first two could not: the candidate's own code, and the gates they had just produced. It found
no CRITICAL, and I verified every finding against source before touching anything — this time none of
them was wrong. The adjudication, finding by finding, is
`docs/reviews/2026-09-17-review3-adjudication.md`.

**The three HIGH ones, all of the same class the brief asked it to hunt.**

- **The artifact half of the Vietnamese gate skipped the file the source half declared.** `scan_jar`
  applied `EXEMPT_DIRS` absolutely and skipped all five bilingual classes unconditionally, including
  `LiteCommand.class` — the whole LITE command surface. So `commands/ItemCodeInput.class`, declared
  `SCANNED` precisely because `LiteCommand` calls it, was invisible in the artifact while the gate row
  advertised 361 scanned classes as independent evidence. Now a class in an exempt directory is skipped
  only if its source is *not* declared LITE-reachable; each declared bilingual class must exist in the
  jar and still carry Vietnamese, so the declaration cannot rot into a free pass; and a contract test
  requires every class entry to be either scanned or in a named bucket (365 + 73 + 5 = 443 today).
- **The truncated-hash rule was blind to a build whose full digest survives nowhere.** `SCREENSHOT_STATUS.md`
  named `c776a020…` as "the build actually running" and the gate printed 0 findings, because a short
  quote was only judged against digests that happen to appear in full somewhere in the scan. Retired
  candidates are now declared one line at a time, and the two live documents that named a non-shipping
  build were rewritten to say so in words rather than being left to imply it.
- **The craft refusal mapping was inverted.** `CANCEL_TAGGED_READY_COPY` — the duplicate case — fell
  through to the message that tells an admin to set `tracking.cancel-untracked-craft-output: false`, a
  key `CraftOutputPolicy.decide` never reaches once the result carries an identity. An admin would have
  changed the setting, restarted, and stayed blocked with no clue why. The mapping is now a pure
  function beside the actions, `messageKeyFor`, with an exhaustive `switch` (a sixth action cannot
  compile until it is given a sentence) and four tests: three refusals, three sentences; only the one
  a config key decides may name that key; every key must exist in all four message sources.

**The MEDIUMs were mostly claims, not behaviour**, which is where this project keeps bleeding: the
throttle that silenced a *different* refusal within five seconds (now keyed by player **and** refusal);
the privacy adjudicator's allowlist, which could not see the overview lines `LiteCommand` sends straight
through `sendMessage` (now a denylist, with a contract test for exactly that shape); two gate rows that
claimed more than the code does (the epoch clause is a backstop that halves the repeat rate, not a
substitute for the window; `detection-cooldown-ms` cannot be set to 0 because it is floored to two
cycles); and three holes in the statement-level language rule — a comment mentioning `vietnamese`
cleared the literal below it, any second literal counted as a translation, and `statements()` split
literals containing `;` or `{`. Tightening that rule immediately paid: it flagged a real literal in
`LiteCommand` on the shipping tree — and then my first version of the rule flagged a *legitimate* pair
(`text("Enabled", "Đã bật")`), which is why the rule now excludes colour codes and config keys by shape
instead of requiring long prose.

**My own defects, found while adjudicating.** `run_release_runtime_gates.py multii` printed
`0/0 gates PASS` and exited 0 — a caller reading the exit code sees success for a run that executed
nothing; an unknown gate name is now a hard error with five contract tests. `finalize_release_candidate.py`
rewrote *any* 64-hex digest in the description to the current one, which turns a claim about a different
artifact into apparent agreement instead of reporting the disagreement; it now refuses. And
`SqliteLossJournalTest.readAndWriteAreQueuedOnTheSerialDatabaseThread` is flaky by construction —
`CompletableFuture.get()` returning does not mean the dependent callback has run — so it failed once
under load while nothing was wrong; it now waits for the callback it asserts on. A gate that fails for
reasons unrelated to the code is as broken as one that cannot fail.

**Cost of doing it properly, recorded honestly:** the mapping fix meant touching product source, which
voids the runtime evidence of the candidate that existed at that moment (`00b40fb1`). I stopped that
gate chain mid-run rather than keeping a build whose mapping was only verified by eye. The candidate is
now **`8c0e540e…`**, and the whole chain was re-run on it: 855/855 Java tests, 67/67 script tests,
107/107 tooling contracts, 7/7 runtime gates, and every offline gate.

### 2026-09-17 (night) — the privacy gate closed, and a fourth gate that could not fail

**Two open rows, one of them now real evidence.** The privacy row said the redaction branch had only
ever been exercised by unit tests, because no fixture put a foreign actor into a member's
owner-scoped window. The `multi` fixture already did — staff drops a tracked item, the member picks
it up, and the history then holds a staff row inside the member's own item — so the scope gained
case C: the member's drilldown must render `another player (staff only)` and `location hidden`,
**and staff's own view of the same rows must contain the name and the coordinates**. That control is
the whole value: without it, "the member did not see the name" is compatible with the name never
being in the data at all. Fixture `itemguard-lite-isolated-4038c31c1425`, adjudicated by
`verify.py multi` from the raw transcripts.

Writing that adjudicator caught the same defect class twice, in my own code:

- The first version searched the **raw log line** for the actor's name. Every line is
  `{"player":"LiteStaff","message":"..."}`, so the *control* ("staff saw the name") was true for
  every staff-addressed line — an assertion that could not fail, guarding another assertion that
  therefore meant nothing. Only the `message` field is evidence.
- The second version required the plugin prefix on every line of the reply, but a timeline reply is
  a prefixed header plus **bare rows** (`2. Dropped | another player (staff only) | location hidden`).
  It read two lines out of seven, called a correct run a failure, and would have missed a real
  disclosure in a row. `is_plugin_answer()` now accepts both shapes and rejects vanilla broadcasts
  ("X joined the game"), which is the line an earlier attempt at this assertion matched to prove
  nothing.

Five contract tests pin all of it, including both vacuous cases. Tooling contracts: **106/106**.

**A fourth gate that could not fail: my own runner.** `reload`, `multi`, `two-plugin` and
`power-cut` write `PASS_*`/`FAILED_*` into a JSON file and exit 0 either way, and
`run_release_runtime_gates.py` was reading the **exit code**. Four of the seven gates were therefore
reported as PASS on a criterion that a failed gate also satisfies. Fixed: the verdict is read from
the file the gate writes, a missing verdict file is a rejection, and only `multi` has an independent
adjudicator today — `independent_verifier: false` is now recorded for the other three rather than
left to be assumed. (Checking the four files by hand afterwards confirmed all four really had
passed on this candidate, so the earlier report was right by luck, not by measurement.)

**And the custody row, closed with rows instead of markers.** `scripts/verify_custody_rows.py`
reads a finished fixture's own SQLite and checks that the numbers the scope reports rest on real
events: fixture `itemguard-lite-isolated-d0dc43a9e6d3` holds one identity (`NDRRO3`, owner
`LiteMember`), 27 history rows, exactly two actors, a real handover (`PICKUP by LiteMember` after the
`SPAWN` by `LiteStaff`) and then **7 genuine handovers**, the largest 2,201 ms apart — every one
inside the 15-minute custody window, which is *why* `transfers=1` is the right answer and not a
coincidence. Four contract tests pin the check, including the three ways it could pass while proving
nothing (a third actor, a handover outside the window, a missing `/clear`). My first version of the
check asserted the swaps alternate, which the real rows do not — they interleave self drop/re-picks —
so the assertion was rewritten to walk holder changes the way the policy defines them.

Recorded honestly: two `multi` runs were rejected before the clean one. `ca8a5543255d` failed the
scope's own two-item seeding (`slot0=null`, intermittent in the probe — the same shape of flake has
been seen in this harness before), and `f2ba586f32d1` passed the scope and was rejected by my first
verifier because it demanded an `outcome.json` this scope does not write.

### 2026-09-17 (evening) — review of the post-review changes, adjudicated and answered

The first review covered fixes that had never been reviewed. This one covered the fixes that answered
it — including the two gates added an hour earlier — and found 5 HIGH, 8 MEDIUM and 5 LOW. Every
finding was verified against source before anything changed; nothing was rejected. The adjudication is
`docs/reviews/2026-09-17-review2-adjudication.md`.

**The three gate defects, because they are the class this project keeps meeting — a check that cannot
fail:**

- **The `.class` scan was reading a quarter of every class.** `class_strings` did not know
  `CONSTANT_MethodHandle` (tag 15), the tag javac emits for every lambda, method reference and
  non-constant string concatenation — and it *returned what it had* instead of complaining. Measured
  on the real jar: **82 of 338** Utf8 constants in `ItemGuard.class`. The declared-bilingual classes
  were skipped, the rest were "checked", and the gate printed `0` — the same shape as the H4 setting
  that could never suppress and the relocation authority that could never fire. Now tag 15 is handled
  and an unparsable class is a **finding**; a test parses a real class and requires more than 300
  strings, which is the assertion the first version would have failed.
- **The language flag exempted a whole file.** `LiteCommand` contains `isVietnamese()`, so every
  literal in it was excused — and `LiteCommand` *is* the LITE command surface. The rule is now
  per-statement, with the three other bilingual shapes in this codebase accepted: a ternary across
  lines, a block whose header carries the flag, and an English literal beside the Vietnamese one. The
  first attempt also accepted `message(`/`text(` as markers, and an older test caught that
  `message("VI only")` is a bare Vietnamese string — the helper names were removed from the rule.
- **The "honest exemption" check could not see the biggest exempt package.**
  `referenced_from_outside` did not match `import com.itemguard.commands.*;` and did not match bare
  class names, so fourteen command classes were never asked whether LITE can reach them. Now they are
  listed one by one with the reason each is unreachable, and a new command class makes the gate red
  until someone declares it.

**The rest of the batch.** The adopted-row marker was only right until the item was next touched
(`created_at = 0` is durable now); the duplicate-alert floor still evaporated when a sweep overran its
cycle, and the fix is an epoch clause gated on a non-zero window (the first attempt broke a documented
behaviour — `0` means "no throttle" — and that test is how the constraint was found); one craft
message served two opposite refusals and two new keys exist; the corrupt-tag refusal no longer tells
the player to retry something that cannot succeed; the pickup that is cancelled so an item can be
tagged now says so; `enabled: false` now disables the plugin rather than leaving other plugins seeing
an enabled plugin with a null API; and a dead branch in `ItemListener` whose contract test asserted an
invariant the dead code never exercised was removed, with the behaviour it implied written down
instead.

**My own errors in this round, both caught by the harness rather than by me:** the `general.enabled`
guard was first placed *after* `loadManagers()`, so the database was already open and the recovery
write already done while the log line said no database work ran — that is why the guard now sits
between `loadConfigManagers()` and `loadRuntimeManagers()`, with a source contract to keep it there.
And the anti-overflow clamp I wrote for the cooldown was applied after the multiplication; my own test
failed on the first run.

Candidate: `80fc610b1e1f3be4c3abb7a163d899fce5d285c23578449dff6b27e08cd167ab`, 5,122,166 bytes —
**851/851 Java tests**, repinned, three copies identical, listing and generated documents rewritten by
`scripts/finalize_release_candidate.py` (new: one command for every step that has to agree on one
hash). Offline: listing copies 0 findings / 6 records, Vietnamese 0 over 180 files and over 361 class
entries, 48/48 script tests, 100/100 tooling contracts, `ARTIFACT_CONSISTENT_OFFLINE_ONLY`.

Runtime, **7/7 gates on this candidate**: `d0dc43a9e6d3` separate · `810109f0a060` loss ·
`7b16cdae2775` sweep · `1d0164787ba2` reload · `05f2c46ff43a` multi · `28ebfcc46808` two-plugin ·
`478d33b8dbb2` power-cut. Version matrix: **9/9 PASS on this candidate, and two of them were re-run to get there.** First pass
(`run/version-matrix-20260917-170539.json`): 1.21.4 `O0O6O2` · 1.21.5 `U9Q7RF` · 1.21.6 `SNJLX4` ·
1.21.8 `WP76OY` · 1.21.11 `ZA5HDS` · purpur:1.21.4 `VH8J3I` · purpur:1.21.11 `IH7064`. **1.21.9**
(`4BJNOD`) and **1.21.10** (`KRODFY`) were recorded `REJECTED` even though both confirmed the planted
duplicate, because their server JVM did not exit on its own: the log shows the shutdown reaching
`[MoonriseCommon] Awaiting termination of worker pool for up to 60s...` after every world saved and
ItemGuard disabled — the known Paper teardown hang, not a plugin result. A fresh attempt on each
(`run/version-matrix-20260917-172435.json`) gave 1.21.9 `TPA1OO` and 1.21.10 `LM6SMH` with both
children exiting 0 and none forced. The strict "no forced kill" rule stays as it is: relaxing it is
how a plugin that blocks shutdown gets through.

### 2026-09-17 — a review of the fixes found eleven more, and the candidate is rebuilt

### 2026-09-17 — independent review of the C/H fixes, adjudicated, fixed, and re-verified

The open row in `LITE_RELEASE_GATES.md` was "no independent review of the *fixed* hash". Filled it:
a read-only adversarial review through the Claude CLI on the fixed code (brief:
`docs/reviews/2026-09-17-fixes-review-brief.md`, report:
`docs/reviews/2026-09-17-independent-review-of-the-fixes.md`), then every finding checked against
source by me before anything was changed — `docs/reviews/2026-09-17-review-adjudication.md` is that
adjudication, including what I rejected and why.

**Real and fixed in this candidate (11):**

- **C2 — the "English-only" jar still printed Vietnamese.** The startup banner
  (`ItemGuard.java:75` "da kich hoat!"), the disable line (`:114`), the craft refusal in chat, and six
  `SEVERE` log lines in `ItemTrackingService`. All reached LITE, because `ItemGuardLite` does not
  override `onEnable`/`registerListeners`. The H5 fix could not see them: it pinned the fallback
  *map*, and `package_lite.py` allowlists resource **file names** while never reading a `.class`.
  Translated to English. H5's own claim of being closed was too strong, and now has a gate.
- **New gate `scripts/check_no_hardcoded_vietnamese.py`** — 178 files scanned, a Vietnamese literal
  is only allowed behind `isVietnamese()`/`vietnamese ?` (the sanctioned bilingual form), 3 FULL-only
  directories exempt and named in the output. 7 tests, including the ASCII-only banner that shipped
  and the exemption boundary. Added to the offline gate list in the release checklist.
- **H3 — every cancellation was silent.** Five sites in `ItemListener` cancel an action when the
  identity is not ready, and after a restart the readiness cache is empty, so the first click, drop,
  drag, use or pickup of a tracked item vanished with nothing said. Now announced through the message
  files, throttled per player (5 s) with a bounded map, plus one key added to all three message files.
  Pinned by `ItemListenerNoticeContractTest`.
- **M1 — my own H4 fix was still inert at its floor.** The suppression test is strict
  (`previous.created_at > now - cooldown`) and two audits are ≥ one cycle apart, so a floor of exactly
  one cycle suppresses nothing. Floor is two cycles. Writing the test for it also found an overflow in
  the same method: a nonsense scan interval multiplied before clamping, went negative, and the whole
  setting silently became 0 — clamped before multiplying now.
- **M2 — a double chest fell out of the cooldown entirely.** Its holder is `DoubleChest`, not a
  `BlockState`, so the key fell to the identity-hash branch: the most common container there is
  re-read its whole inventory on every open. Own branch by location, three new test cases.
- **H5/M5 — `general.enabled` was a switch that did nothing.** Documented as "Enable/disable the
  entire plugin", read by nothing, so an admin who hit a problem and did what the config said saw no
  change. Honoured before anything is registered.
- **M4 — adopted rows claimed `created_at` was the adoption.** `/ig check` printed "Created: today"
  for a two-year-old sword whose identity came from a restored database — the number staff would use
  to convict someone. Now "unknown (adopted)".
- **H4 — a comment asserting a safety property the code does not have.** C2's comment said the
  ender-chest reconcile "cannot mint anything"; it can adopt (that is C3's intent, reached through
  `unresolvedHolder`). Corrected, so anyone tightening C3 later knows this path mints too.
- **C1 — the plugin cancelled every crafted tool, weapon and armour piece by default, and the page
  did not say so.** `CraftOutputPolicy` cancels an eligible untagged output; `TrackingWorthinessPolicy`
  counts every `*_PICKAXE/_SWORD/_HELMET…` as eligible; a fresh LITE install therefore refused a stone
  pickaxe in Vietnamese. A controlled runtime gate had verified that cancellation *on purpose*, and
  the only place it was written down was `docs/release/ItemGuard-LITE-1.0.0-README.txt` — which the
  buyer never receives, since the listing uploads the jar alone. Added
  `tracking.cancel-untracked-craft-output` (default `true`, so no silent behaviour change; `false`
  lets the craft through and the identity arrives at the next scan or pickup), and the Spigot page now
  says it plainly with the config key. **Recommendation recorded for Thanh: ship `false`** — one line
  in `lite/config.yml`, no rebuild.
- **H2 — a tracked item cannot move into or out of a storage minecart.** Kept fail-closed rather than
  inverting a runtime-verified property without his decision, but the page now states it instead of
  implying only that minecarts are not *read*.

**Rejected, with reasons.** C3's proposed fix — publish a `PREPARED` publication from a different
physical source when the digest matches — is exactly the "unsafe relocation authority" an earlier
independent review removed on 2026-08-24, and `TagPublicationRelocationRepositoryTest` asserts the
refusal in three cases. The consequence is real and stays: after a crash in that window, a moved item
is unusable until it is returned to its original source or staff intervene — and it is no longer
silent. H1's two proposed tightenings were both tried against the SQL and the existing rule: "adjacent
epoch only" and "require the intersection of the two epochs' locations" both fail to separate
"moved twice, identically" from "two copies" — at the observation level they are the same data. The
real fix is to consult the item's own transfer history, which is a spec decision.

**Two harness pins broke on those fixes, and the harness refused to pass.** The reload gate waited for
the Vietnamese disable line and the smoke scope for the Vietnamese craft refusal — strings this
candidate no longer prints. Both were corrected in `tools/lite-runtime/`, and only those two gates
were re-run, on the same jar (nothing was rebuilt, so the five that had already passed stay valid).
`test_contracts.py` 100/100 after the correction.

Candidate: `28aed104cf9a01bae98373016bafe5033a398533447a56868381433c66b14a30`, 5,121,013 bytes —
**846/846 Java tests** (`BUILD SUCCESS` 15:58:47), repinned across `smoke.py`/`manual.py`, copied to
all three locations, `SHA256SUMS.txt`, the SHA in `description.bbcode.txt` (both copies), and the
generated preview and handoff all refreshed. Offline: artifact verifier
`ARTIFACT_CONSISTENT_OFFLINE_ONLY`, listing copies 0 findings / 5 records, Vietnamese literals 0 (source *and* jar constant pools), 33/33
script tests, 100/100 tooling contracts.

Runtime, **7/7 gates on this candidate**, each on its own real server and adjudicated by its own
verifier: `705dc454eceb` separate · `378971d9dbf2` loss · `422bf10e78b0` sweep · `8305460c4b4f` reload
· `b2e3cdc2cdda` multi · `d8c4f2cdf02b` two-plugin · `8d77eb9f6a6a` power-cut. Version matrix: see
`run/version-matrix-*.json` — the nine versions this harness can certify, run with
`scripts/version_matrix.py`; `1.21.7` and the `26.x` line remain **untestable here**, not failures.

Matrix result: **9/9 PASS on this candidate** (`run/version-matrix-20260917-161211.json`), each on
its own real server with its own staged jar hash and its own confirmation code:

    1.21.4 Q2P00W · 1.21.5 K4OMP1 · 1.21.6 PTZOYR · 1.21.8 NFZN24 · 1.21.9 GF084I
    1.21.10 MO6BHK · 1.21.11 LRY365 · purpur:1.21.4 C32CFK · purpur:1.21.11 NJYYGF

`1.21.7`, `26.1.1`, `26.1.2`, `26.2` remain untestable by this harness (no protocol in the pinned bot
libraries), so the listing says nine and names those four as untested. Cleanup verified by looking
rather than by taking the harness's word: **no java process left running** at all, and each matrix
entry records `port_released` with `smoke_exit 0`.

**`.hermes/WORKING_STATE.md` is stale and could not be updated in this session.** Writing to it was
refused at the tool-approval layer (the approval prompt timed out). It still names candidate
`94eb0dad…`, which is void. `CURRENT_STATE.md` and `docs/release/LITE_RELEASE_GATES.md` are the
authoritative pair until someone approves that write; do not read the working-state file for the
current candidate.

New tooling: `scripts/run_release_runtime_gates.py` (sequences the seven gates, one server at a time,
and records each verdict as JSON — running two Paper servers at once is what exhausted native memory
on the 2026-09-17 matrix), `scripts/test_check_listing_copies.py` (8 tests for the listing gate's own
false-exemption hole).

### 2026-09-17 — handover surface reconciled, and the new gate had a hole of its own

The previous session left `scripts/check_listing_copies.py` written but **red**: four files still
pointed at `a952d161…` while the folder held `94eb0dad…`. Working that to green turned up more
than the four files.

**The gate could not fail on the two files that mattered most.** Its record rule was "the word
SUPERSEDED appears in the first 20 lines", so a file merely *mentioning* that some other artifact
was superseded exempted itself. Measured on the real tree before the fix: `LITE_RELEASE_GATES.md`
("Candidate `e11ada6a…` is **SUPERSEDED**") and `SCREENSHOT_STATUS.md` (a table cell reading
"Superseded") were both exempted while calling `a952d161…` their *current* and *shipping* jar —
exactly the drift the gate exists to catch, reported as `0 findings`. The rule is now "a line that
*starts* with the marker, after stripping `#>` `*`", and `scripts/test_check_listing_copies.py`
pins it with 8 tests, including the prose-mention and table-cell shapes that used to pass. Script
suite: **22/22 OK** (`python -B -m unittest discover -s scripts -p 'test_*.py'`).

**A real defect in the handover the owner was about to act on.** `build_spigot_handoff.py` told the
form-filler to enter `Languages Supported: English, Tiếng Việt`. The LITE jar ships
`messages_en.yml` only — `MessageLanguagePolicy` forces `en` for the edition whatever `config.yml`
says, and `package_lite.py` refuses any root resource outside `{plugin.yml, config.yml,
messages_en.yml}`. Vietnamese was a false support claim, the same class as the `26.x` tick the
generator had already learned to stop writing. The field is now **read out of the jar's own
`messages_*.yml` entries**, and an unnameable language is a hard stop rather than a guess.

**`docs/release/paste/README.md` is the runbook the owner follows, and it was stale in four ways:**
the old title (`Item Logger, History Tracker & Duplicate Detection`), "Tested Minecraft Versions →
tick every entry from 1.21 upward", the old jar's SHA-256 and its 5,113,520 bytes, and a language
instruction that contradicted the handoff beside it. Rewritten to contain **no copied claim
values at all** — every field points at the generated file — and `01-resource-name.txt` is now
written by the generator instead of by hand, so it cannot drift from the title field again.

Files resolved honestly rather than rewritten into agreement: `SPIGOT_PAGE_FINAL.md` and
`SPIGOT_SUBMISSION_FORM.md` marked SUPERSEDED with the specific false claims named (including a
version table that still marks `1.21.7` and the `26.x` line PASS — the `13/13` claim is
unrepeatable); `LITE_RELEASE_GATES.md` rebound to `94eb0dad…` with every row re-derived;
`SCREENSHOT_PLAN.md` / `SCREENSHOT_STATUS.md` rebound, and the banner strings re-grepped against
the shipping source with line numbers; `TEST_PLAN_AND_PREMIUM.md`'s `796/796` and `13/13` replaced
by the current binding. Three files are deliberate records (one dated, two self-declared
SUPERSEDED) and the gate now prints them **with the reason for each exemption** instead of passing
over them in silence.

**Verified independently rather than taken from the prose:** all six runtime fixtures plus the
aborted-run leftovers carry `stage.json`/`attempt.json` bound to `94eb0dad…` on disk
(`565c3bce5268` separate/2 generations, `2fb7e5f9b318` loss, `8ca467a3aef8` sweep, `561053bb287b`
reload, `f8fbe0e9b60c` multi, `ff681c44a63b` two-plugin, `f11df8071f9d` power-cut);
`run/version-matrix-20260917-020929.json` holds nine entries, every one `PASS_SWEEP_SMOKE` and
every one carrying `candidate_sha256 94eb0dad…`; `docs/reviews/lite-package-build.log:549` is
`Tests run: 835, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`, 01:59:48.

Re-run this session, on the jar that would be uploaded: `verify_lite_artifact.py` →
`ARTIFACT_CONSISTENT_OFFLINE_ONLY`; `tools/lite-runtime/test_contracts.py` → **100/100 OK**;
`check_listing_copies.py` → **0 findings, 5 records named**; both generators idempotent (a second
run produced byte-identical `_PASTE-THIS-TO-CLAUDE.md` and `PREVIEW.html`). No source file was
touched, and the jar in all three locations still hashes `94eb0dad…` — so the 7 runtime gates and
the 9-version matrix remain bound to it.

Housekeeping: `E:` has 31.7 GB free; the 17 `itemguard-lite-isolated-*` fixture roots name the
**current** candidate, so `prune_superseded_fixtures` will not collect them and they must not be
deleted while they are the release evidence. `itemguard-lite-isolated-fab323a823c2` (86 MB) has no
`stage.json` and no `attempt.json` — a staged-but-never-run root — and was left in place.

Still the owner's step, unchanged: client acceptance and the upload itself. Open rows in
`docs/release/LITE_RELEASE_GATES.md` are the privacy redaction runtime case, custody rebound,
crafting issuance, natural break/IG-R016, and an independent review of the **fixed** hash — the
Opus 5 audit reviewed the code that was wrong, not the code that replaced it.

### 2026-09-16 — three Critical defects fixed (C1, C2, C3)

Found by an independent Opus 5 review of the whole repository (not by any failing gate: 806 tests
were green against every one of them), then verified against source before being fixed. All three
are the class of defect this project keeps finding by playing rather than by reading — the code
does exactly what it says, and what it says is wrong for the player.

**C2 — a tagged item in an ender chest or storage minecart was unusable forever after a restart.**
Readiness lives in an in-memory cache (`IdentityReadinessCoordinator:24`), so a restart empties it,
and the only paths that refilled it were the player-inventory and container scans — both of which
skip holders whose physical slot cannot be resolved (`ItemTrackingService:974-975`, `:995-999`).
Every click on such an item was cancelled, silently. Fixed in `ItemTrackingService.isIdentityReady`
by reconciling on contact, using a new `TagPhysicalSourceKey.unresolvedHolder(...)` key whose
namespace no publication can occupy, so the call can confirm an existing identity and cannot
complete or mint one. One site, all seven callers.

**C3 — a tagged item whose canonical row was gone could not be picked up and therefore despawned.**
`reconcileTagPublication` (`ItemSqliteRepository:1411-1452`) answered false for everything that was
neither a canonical row nor an exact in-flight publication match, permanently, and a cancelled
pickup means a ground item despawns. The database lost one row and the player lost the item.
Thanh's decision: adopt — but only when the journal has never held that code or uuid at all
(never for an identity the journal knows, which stays fail-closed). The adopted row records
`last_action = 'ADOPTED'` plus an `ADOPTED` history row, so the audit trail still shows it was not
an original issuance.

**C1 — the sweep called a legitimate move a confirmed duplicate.** An epoch spans real time: the
player scan completes in one tick, while the container sweep spreads over up to a minute and writes
into the same epoch (`InventoryScanTask:86-94`). An item carried and then stored is seen at two
locations in one epoch — which the audit read as a duplicate. Thanh's decision: a confirmation now
also requires the identity at two or more locations in the previous epoch, which a moved item
cannot be. Cross-source detection (one copy in a hand, one in a chest) is preserved.

Evidence: **816/816 Java tests, BUILD SUCCESS.** Each fix has an assertion-level RED and a
mutation check (C2: removing the call re-fails the test; C1: both new tests failed with the real
false positive, `expected: <0> but was: <1>`). Seven existing tests in `ItemSqliteRepositoryTest`
encoded the old one-epoch rule and were updated by seeding the preceding epoch, with every original
expectation left unchanged — no threshold was loosened to make them green.

**The LITE 1.0.0 release candidate is now stale.** That jar was built from code that contains all
three defects; nothing uploaded should claim the fixes. Package a fresh candidate and re-run the
release gates before any upload. Also note the review's H5 finding: `MessageManager.DEFAULT_MESSAGES`
is Vietnamese, is compiled into the class, and ships inside the English-only LITE jar.

Also landed: MySQL Premium groundwork — `DatabaseBackend.MYSQL` is recognised and
`requireImplemented` refuses it by name with an actionable reason instead of reaching `DriverManager`.
Design and five locked decisions: `docs/design/2026-09-16-premium-mysql-contract.md`.

### Candidate 94eb0dad…13de3c — every gate this harness can run is green (2026-09-17)

    PASS  835/835 Java tests, 0 failures, 0 errors, 0 skipped   (gate inside package_lite)
    PASS  ARTIFACT_CONSISTENT_OFFLINE_ONLY                      (scripts/verify_lite_artifact.py)
    PASS  100/100 tooling contracts                             (tools/lite-runtime/test_contracts.py)
    PASS  candidate hash recomputed independently — matches the packager's claim

    7/7 runtime gates, each adjudicated by its own independent verifier:
      PASS_CONTROLLED_SMOKE  fixture 565c3bce5268   4 children exit 0, none forced, port 58225 closed
      PASS_LOSS_ONLY_SMOKE   fixture 2fb7e5f9b318   port 64559 closed
      PASS_SWEEP_SMOKE       fixture 8ca467a3aef8   port 50292 closed
      PASS_RELOAD · PASS_MULTI · PASS_TWO_PLUGIN · PASS_POWER_CUT

    9/9 version matrix PASS, each version on its own real server with a real bot:
      1.21.4 · 1.21.5 · 1.21.6 · 1.21.8 · 1.21.9 · 1.21.10 · 1.21.11 · purpur:1.21.4 · purpur:1.21.11
      evidence run/version-matrix-20260917-020929.json

Cleanup verified independently after the fact rather than taken from the harness's own summary:
every fixture port refuses connections and every child exited 0 without being forced, across all
nine matrix fixtures and all seven runtime fixtures.

**The compatibility claim this candidate supports is nine versions, not thirteen.** `1.21.7`,
`26.1.1`, `26.1.2` and `26.2` cannot be certified by this harness — the pinned bot libraries carry
no protocol for them — so they must be described as untested, never as failures and never folded
into a larger number. The listing still says thirteen and must be corrected before upload.

Still to do before anything is published: replace the stale jar in `release/spigot-upload/` (it is
still `a952d161`, the one with the three Critical defects), regenerate the handoff
(`scripts/build_spigot_handoff.py`), correct the listing's version count, and the owner's own client
acceptance and upload.

### Three High findings fixed after the review — and this code is AHEAD of every verified candidate

H4, H1 and H3 from the same review, batched so the rebuild and the runtime gate re-run happen once
instead of three times. All three have mutation-proven tests: breaking each guard made 3/5, 2/3 and
1/5 of its tests fail, so none of them is a test that cannot fail.

- **H4** — `anti-dupe.detection-cooldown-ms` shipped as 5 s against a 30 s scan cycle, so it could
  never suppress a repeat: the same identity is re-detected one cycle later, always outside the
  window, and staff were re-alerted forever while a finding row accumulated each cycle. The window
  is now floored at one scan cycle (`AntiDupeSettings.detectionCooldown`), the shipped default moved
  5000 → 300000, and a test loads the actual `config.yml` so an inert value cannot ship.
- **H1** — a pending chat filter had no expiry: press Filter, walk away, and the next message —
  hours later, mid-conversation — was cancelled and read as a filter query. Now a 30 s TTL, the
  player is told the window, and a stale request leaves their message alone.
- **H3** — the container cooldown was keyed on `holder.toString()`; a block container prints its
  coordinates and happened to work, anything else printed an identity hash that changes per
  snapshot, so the cooldown never fired and the map grew for the life of the process. Keys are now
  derived from stable identity and the map is pruned and capped at 4,096 entries.

**Consequence, stated plainly: the code has moved three fixes past candidate `082d73b3…7f2d9c`, so
all nine gates that verified it are void.** Nothing may be uploaded until the candidate is rebuilt
and the 7 runtime gates plus the 9-version matrix have run again on the new jar. The remaining
half of H4 — `duplicate_findings` having no retention — is deliberately not done: LITE promises
never to prune history, so deleting findings would destroy investigation evidence, and that is a
product decision, not a code one.

### H5 fixed (code side), and the candidate rebuilt from the fixed code — offline gates only

H5 was the "the listing says one thing, the jar does another" defect: the jar shipped no
Vietnamese *files*, but a Vietnamese *map* was compiled into `MessageManager.class` and used as the
fallback for every key a message file does not define, and a LITE server configured `language: vi`
wrote the removed `messages.yml` straight back onto disk. Fixed:

- `MessageManager.DEFAULT_MESSAGES` is now the English text, pinned key-for-key and value-for-value
  to `messages_en.yml` by `MessageManagerFallbackTest` — so a key added in code without an English
  entry fails the build instead of showing a player Vietnamese.
- `MessageLanguagePolicy` decides the language by edition: LITE is English, always. Vietnamese
  remains a FULL feature (`ConfigManagerLanguageTest`, 6 cases, including that FULL still honours it).
- `lite/config.yml` no longer advertises `# en or vi`.

Still open from H5 (recorded as IG-R020): both packaging gates still deny resource file *names*
rather than allow-listing entries, and neither inspects `.class` content.

| | |
|---|---|
| Candidate | `082d73b34e83f29d98f21a2ee88eaf7d29068b38acfc21be0db7ab9f5d7f2d9c` |
| Source (FULL) jar | `24990b2d60120a2f0db4485a89f17c80be81e8eb3573289271c2816ef1baba23` |
| Built by | `scripts/package_lite.py` (runs `mvnw -o clean verify` as its own gate) |

    PASS  823/823 Java tests, 0 failures, 0 errors, 0 skipped   (inside package_lite)
    PASS  100/100 tooling contracts   (tools/lite-runtime/test_contracts.py, exit 0)
    PASS  ARTIFACT_CONSISTENT_OFFLINE_ONLY   (scripts/verify_lite_artifact.py)
    PASS  candidate hash recomputed independently — matches the packager's claim
    PASS  PASS_CONTROLLED_SMOKE   (REAL PAPER — independent verifier on fixture
          itemguard-lite-isolated-b0099f9369ed; 4 children exit 0, none forced, port 54750 closed)
    PASS  PASS_LOSS_ONLY_SMOKE   (REAL PAPER — verifier `verify.py loss` on fixture
          itemguard-lite-isolated-d363f6ec4717; 2 children exit 0, none forced, port 52331 refused)
    PASS  PASS_SWEEP_SMOKE   (REAL PAPER — verifier `verify.py sweep` on fixture
          itemguard-lite-isolated-05579cbb8e5b; 2 children exit 0, none forced, port 64452 refused)
    PASS  PASS_RELOAD   (REAL PAPER — reload.py on fixture itemguard-lite-isolated-6771cd363790;
          identity ASJEWP before and after `/reload confirm`, every listener still exactly one
          registration, no history row duplicated, no errors during reload, port 58123 refused)
    PASS  PASS_TWO_PLUGIN   (REAL PAPER — two_plugin.py on fixture itemguard-lite-isolated-792a6eb40ee0;
          BastionForgeLite 0.1.0 loaded alongside ItemGuard LITE, identity 11Y9P5 unchanged by the
          foreign plugin's edit while the digest did change, port 50460 refused)
    PASS  PASS_MULTI   (REAL PAPER — multi.py on fixture itemguard-lite-isolated-4138185f59b5;
          two tracked items side by side kept distinct identities, member and staff querying one
          item concurrently agreed on one identity with consistent history, port 52861 refused)
    PASS  PASS_POWER_CUT   (REAL PAPER — power_cut.py on fixture itemguard-lite-isolated-ce50d4361174;
          `taskkill /F` mid-write, then reboot on the same world and database: every ItemGuard
          record for the identity survived — tracked_items 1, item_history 1, item_snapshots 1,
          tag_publications 1 — no rollback journal left behind, port 51025 refused)

    NOT RUN on this candidate: version matrix (13) — attempted and abandoned, see below.

**The version matrix did not run, and its result must not be cited in either direction.** The
attempt (13 versions, `run/version-matrix-20260917-004340.json`) produced 11 consecutive
`REJECTED` before I stopped it, and neither the passes nor the failures are product evidence.

Two separate infrastructure faults, both verified rather than assumed:

1. **Native memory.** Every server died with
   `insufficient memory for the Java Runtime Environment … malloc failed to allocate 1015376 bytes`,
   leaving `hs_err_pid50920.log` behind, before the plugin could enable. The host has 32.7 GB total
   and 5.3 GB free with the owner's launcher running; a Paper server needs native memory beyond its
   heap. This is why `plugin_enabled: False` on **all** versions including 1.21.11, which had passed
   the controlled smoke 20 minutes earlier.

2. **The per-version jar binding was broken — cause found, fixed, and verified without booting a
   server.** The entry recorded as `1.21.4` (build 232) staged a `paper.jar` hashing
   `6c099540fccd27e1`, exactly `E:/AI.WORK/itemguard-paper-smoke/paper.jar` — Paper **1.21.11** — and
   its `stage.json` recorded that same hash, so it was staged wrong rather than swapped later. The
   cause: `version_matrix.py` chained `set "ITEMGUARD_PAPER_JAR=…" && python …` through
   `cmd.exe /d /s /c`, and with `/s` cmd strips the first and last quote of the whole string, which
   unbalances the remaining quotes so the final `set` never takes effect. Reproduced with no server:
   the child printed `ITEMGUARD_PAPER_JAR = None`, `smoke.py` fell back to the default jar, and the
   record kept the requested build number. **Every version in a run like that boots the same server
   while the matrix reports thirteen.** Fixed by passing the environment directly to `subprocess`
   instead of quoting it, and a mandatory check now refuses any fixture whose `paper.jar` is not the
   jar that version resolved to. Verified through the real function: `stage_fixture` for 1.21.4 now
   stages `5ee4f542f628a14c` (= `paper-cache/1.21.4-232.jar`), `BINDING OK`.

   The consequence still stands for the record: the earlier `13/13` ran through this same code path,
   so it is **unsupported** until re-run with the binding proven. Not disproven about that run —
   unsupported, which is the same thing for a claim.

State left behind: the aborted run is stopped, no fixture java survives, the owner's launcher was not
touched (it had already exited). Free memory is now 7.3 GB of 32.7 GB. The aborted run left ~11
fixture roots and the diagnosis left 2 staged-but-never-run roots (~8 GB together); they name the
**current** candidate, so `prune_superseded_fixtures` will not collect them; disk is at 36 GB free.

### Matrix batch 1 (`1.21.4 1.21.5 1.21.6 1.21.7`) — the binding fix works, and it exposed why the old 13/13 cannot be true

The binding fix is proven in a real run: the entry for 1.21.4 staged
`paper.jar = 5ee4f542f628a14c` (= `paper-cache/1.21.4-232.jar`) and its server log reads
`Paper version 1.21.4-232-ver/1.21.4`. The version labels are real now.

All four versions still came back `REJECTED`, and **the cause is not the plugin**:
`plugin_enabled: True` on every one, the server booted, ran and shut down cleanly
(`exit 0`, ports released). The bots child exited 1, and its log says why:

    Error: This server is version 1.21.4, you are using version 1.21.11,
    please specify the correct version in the options.

`bots.cjs` connects with the client version hard-pinned to 1.21.11. On a genuine 1.21.4 server the
bot is refused, the harness fast-fails on `BOT_ERROR`, the sweep cases never run (the fixture log
contains `LITE_PROBE_BOOT` and nothing else), so no duplicate is ever confirmed and the entry is
recorded `REJECTED` with the generic reason "incomplete or failed attempt".

**Together with the binding bug this closes the question about the earlier 13/13.** That run
booted thirteen 1.21.11 servers (binding bug) and its bots could not have connected to anything
older (version pin), so thirteen passes with thirteen distinct codes is exactly what thirteen
1.21.11 runs produce. The claim was never compatible with the evidence — it is now unsupported by
direct measurement rather than by suspicion, and it must not be cited.

**Both harness fixes are now proven, and the matrix produces real evidence.** `bots.cjs` already
supported a per-server client version through `ITEMGUARD_BOT_MC_VERSION` (added 2026-09-15, after the
old matrix ran) — the caller simply never set it, and the same `cmd.exe /s` chaining dropped
`JAVA_HOME` for the smoke and verify children as well. All three call sites now pass the environment
directly.

First genuine per-version result, `python scripts/version_matrix.py 1.21.4`:

    version   status            dupe  code     locations   staged paper.jar   server log
    1.21.4    PASS_SWEEP_SMOKE  True  F1HXQB   2           5ee4f542f628        Paper version 1.21.4

with no `BOT_ERROR` in the bot log and `ITEMGUARD_DUPLICATE_CONFIRMED … locations=2 action=NOTIFY`
on the server. This is the first time a version in the matrix was actually the version it claims, on
a server that really booted it, with a bot that really connected, confirming a real duplicate.

Batch 2 (`1.21.5 1.21.6 1.21.7`) gave two more genuine passes and one capability limit:

    1.21.5  PASS_SWEEP_SMOKE  EGBFG6  2  staged jar 2ae6ae22adf4  server log Paper 1.21.5-114
    1.21.6  PASS_SWEEP_SMOKE  KU9ZUN  2  staged jar 35e2dfa66b34  server log Paper 1.21.6-48
    1.21.7  REJECTED, bot error: "This server is version 1.21.7, you are using version 1.21"

That last line is not a plugin failure. `version: "1.21.7"` is not recognised by the pinned client
library, so it falls back to the protocol family `1.21` and the server refuses it. The pinned
`minecraft-data` version list decides what the harness can honestly test at all:

    SUPPORTED     1.21.4  1.21.5  1.21.6  1.21.8  1.21.9  1.21.10  1.21.11
    NOT IN LIST   1.21.7  26.1.1  26.1.2  26.2

So the honest maximum for this harness is **9 of 13** — seven Paper versions plus
`purpur:1.21.4` and `purpur:1.21.11`, whose client version resolves to 1.21.4/1.21.11 and is
supported. `1.21.7`, `26.1.1`, `26.1.2` and `26.2` **cannot be certified by this harness** with the
pinned bot libraries, and must be reported as untested rather than as failures. A "13/13" claim is
not attainable here at all; the number that can be defended is 9, and only after each is run.

**Matrix finished for every version this harness can certify — 9 of 9 PASS, each with its own real
server, its own staged jar hash and its own confirmation code:**

    1.21.4          PASS  F1HXQB    staged jar 5ee4f542f628   Paper 1.21.4-232
    1.21.5          PASS  EGBFG6    staged jar 2ae6ae22adf4   Paper 1.21.5-114
    1.21.6          PASS  KU9ZUN    staged jar 35e2dfa66b34   Paper 1.21.6-48
    1.21.8          PASS  JQRB65
    1.21.9          PASS  VRVRUV
    1.21.10         PASS  MRLS15
    1.21.11         PASS  HUM8K3
    purpur:1.21.4   PASS  2MNG2R
    purpur:1.21.11  PASS  LWMMB5

NOT CERTIFIABLE by this harness: `1.21.7`, `26.1.1`, `26.1.2`, `26.2` — the pinned bot libraries
have no protocol for them, so they must be reported as untested, never as failures and never folded
into a bigger number. The defensible compatibility claim for this candidate is therefore **nine
versions**, and the listing must say nine or the harness must be upgraded first.

All nine runs released their ports (verified: every fixture port refuses connections, no server
process left). Evidence: `run/version-matrix-20260917-*.json`.






**`PASS_POWER_CUT` gates the records, not the item — read it that way deliberately.** The run also
reports `identity_survived: false` (the item read back as `NONE`), and that is neither a surprise
nor a product failure: a player's inventory lives in vanilla `playerdata/<uuid>.dat`, written on
logout or autosave, so killing the JVM before an autosave rolls the player back and every item goes
with it, tracked or not. The harness's own words: asserting the item survives "would be asserting
that a plugin can undo a vanilla world-save loss, which it cannot". What is ItemGuard's to guarantee
is its own record, and that is what the verdict is computed from (`records_survived`). Someone
reading only the field name `identity_survived` would draw the opposite conclusion, so it is written
down here.

The same harness contains a second instance of a measurement that could never fail: comparing the
readback by substring against the whole line matched the echoed `expected=<CODE>` in both the success
and failure cases. It compares the first field only.

**What multi actually separated, worth recording because the two halves answer different worries.**
Two tracked items in one inventory (`HJKN8J` and `ZSM7NV`) stayed distinct, which is the class of
bug a single-item fixture can never see: read the wrong slot with one tracked item and you find
nothing, so it fails loudly, whereas with two side-by-side it finds the *other* tracked item and the
mistake looks like success. And two readers on the same identity agreed: the holder's client saw
`HJKN8J`, the staff bot saw no id and was **told** so (`staff_told_no_id: true`) rather than being
silently ignored, `one_identity_for_both: true`, `history_consistent: true`.

**The sweep gate is the one that exercises the C1 rule change, and it still detects.** The fixture
plants one identity in two never-opened chests and the plugin confirmed it on real Paper:
`ITEMGUARD_DUPLICATE_CONFIRMED code=ZJN8GJ … locations=2 action=NOTIFY`, and the fixture database
holds exactly one finding row, `('ZJN8GJ','CONFIRMED',2,1789578840110)`.

The timing is worth stating, because it is the cost of the rule Thanh chose: the probe observes the
planted pair at 00:13:06 and the confirmation is logged at 00:14:00 — about 54 seconds later, i.e.
the second scan epoch. Under the old one-epoch rule this would have fired inside the first pass, and
that is exactly the behaviour that also called every legitimate move a duplicate. One extra cycle to
stop false alarms is the trade that was decided.


**loss-only needed a harness fix, and here is the whole story — including the part that was
wrong on my side.** The first attempt failed case `loss-burn-recorded` on fixture
`…-3af8e9ec3746`, which is consumed and was not replayed. The verdict needs `rowsAfter >
rowsBefore`, and the probe measured `rowsBefore=0 rowsAfter=0` while its own next statement, same
filter, same table, returned `reason=BURNED`. Read back from that fixture's database:
`item_history ('NVAYM1','f0118d09-…','BURNED')` and `tracked_items.last_action='BURNED'` — the
plugin had recorded it, and had logged `loss code=NVAYM1 reason=BURNED actor=server`.

The defect was in the measurement: the listener reports a departure only by comparing snapshots 40
ticks apart, while the probe read the database in the single tick the entity disappeared. Fixed in
`LiteProbe.java` with a bounded wait (`LOSS_ROW_GRACE_SAMPLES`) for the row the case is actually
about, leaving the lava in place while waiting so the case stays a real positive control; a
read failure during the wait is not allowed to decide the case (`lossRowsOrUnknown`). Re-ran on a
fresh fixture: `LITE_LOSS burn gone=true rowsBefore=0 rowsAfter=1 reason=BURNED arenaCleared=true`
→ `LITE_CASE loss-burn-recorded PASS`, and the independent verifier returned
`PASS_LOSS_ONLY_SMOKE`. Re-running the first fixture until it went green was never an option and
was not done.

I also called the wrong verifier on the first attempt (`verify.py <root>` instead of
`verify.py loss <root>`), which reported `REJECTED / wrong attempt scope`. That was my error, not
a plugin result — the full-run verifier requires a two-generation fixture by design.



Verifier output on the runtime gate: `PASS_CONTROLLED_SMOKE`, `candidate_sha256 082d73b3…7f2d9c`,
with its own stated limits — `No client visual acceptance` and
`No crafting/natural-break/scale/crash/guardian claims`. Those limits are real and are not closed
by this run. The fixture is consumed; no replay.


The harness pins (`tools/lite-runtime/smoke.py`, `manual.py`) were moved to this candidate. That is
what makes a rebuild's consequences explicit: **every runtime fixture that ran on the earlier
candidates is void for this jar**, and `smoke.py` prunes superseded fixtures by candidate hash.

**`release/spigot-upload/ItemGuard-LITE-1.0.0.jar` is still the old jar** (`a952d161`) and the
handoff documents beside it say to upload it. Do not upload from that folder. Replacing it and
regenerating the handoff (`scripts/build_spigot_handoff.py`) is a release step that belongs after
the runtime gates have run on this candidate, not before.



## SUPERSEDED — 2026-09-16 — LITE 1.0.0 candidate a952d161 (evidence invalidated by the fixes above)

Candidate `a952d16146082ffacfbcbf6032fdba0634c86e8099cbf6802685c2f76db71727` (5,113,520 bytes).
Every gate below ran on THIS jar, which does NOT contain the C1/C2/C3 fixes.


    806/806  Java tests          100/100  tooling contracts
    13/13    version matrix      Paper 1.21.4-26.2 + Purpur 1.21.4/1.21.11
                                 13 distinct confirmation codes
    PASS_SWEEP_SMOKE (Spigot)    PASS_LOSS_ONLY_SMOKE (Paper)
    PASS_CONTROLLED_SMOKE        ARTIFACT_CONSISTENT_OFFLINE_ONLY
    PASS_MULTI  PASS_RELOAD  PASS_TWO_PLUGIN  PASS_POWER_CUT

Three-agent adversarial audit closed 4 real defects, all of the same kind — code that
reads correct but tells the user something false:

  - alert claimed "both coordinates"; the plugin only sends a COUNT
    (InventoryScanTask.java:153, messages_en.yml:41) — listing corrected
  - startup banner hardcoded "Minecraft: 1.21.11" on every server — now prints the
    real server version
  - "/ig check" said only "This item has no tracking ID" — the first command every
    admin runs, usually holding a stackable block, which is excluded by design.
    Now says why.
  - messages.yml (Vietnamese) shipped inside the English-only LITE jar. Never loaded,
    so editing it did nothing. Dropped, with a build gate and an artifact gate that
    both fail if it returns — the artifact gate was verified by re-injecting the file
    and confirming REJECT.

Waiting on Thanh: read release\spigot-upload\PREVIEW.html, then upload. Not published
by the agent.

## History

## 2026-09-14 — ItemLossListener: damage cause is not proof of destruction

- REAL DEFECT fixed: `onGroundItemDamaged` wrote a `BURNED`/`VOID` loss row on any FIRE/FIRE_TICK/LAVA/VOID `EntityDamageEvent` against a ground item, with no check that the entity actually stopped existing. Both reasons report `confirmsDestruction()`, so a surviving item ended up recorded as destroyed — the same false-confirmed-loss dupe path as the earlier `DEATH`→`CLEARED` bug.
- Root cause confirmed against the pinned `paper-api-1.21.11-R0.1-20260511.115010-91.jar`: `Item` has health (default max 5, "a non-positive value will destroy the entity") and `EntityRemoveEvent.Cause.OUT_OF_WORLD` is documented as applying only to entities removed immediately because "some entities get damage instead". Damage is survivable, so it is not a terminal signal.
- Fix is conservative and grants no restore: damage now only *remembers* the reason per entity UUID; the new `onGroundItemRemoved(EntityRemoveEvent)` records the loss only when the removal cause is in the fail-closed allowlist `DEATH`/`DISCARD`/`OUT_OF_WORLD`. `PICKUP`, `MERGE`, `UNLOAD`, `PLUGIN`, `DESPAWN`, null and unknown future causes record nothing. `EntityRemoveEvent` is present and not deprecated in that exact jar and was previously unused in the repo. `ItemDespawnEvent` deliberately untouched.
- Verification on this tree: `./mvnw.cmd -o clean test` **651/651 PASS** (was 644, +7 new), scripts unittest 10 OK, `package_lite.py` 651/0/0/0, `verify_lite_artifact.py` `ARTIFACT_CONSISTENT_OFFLINE_ONLY`. Candidate `cd8626b2ab22a0e4b2a70bc6ff0e1c6dcd92680af0beb2817bf5ac23cece2147`.
- Red-green is real, not assumed: RED was observed as `recordLoss(...) Never wanted here`, and non-vacuity was proven by mutation (adding `PICKUP` to the allowlist failed exactly the 3 tests that must catch it, then reverted). Two harness defects that would have faked a pass were fixed: real `ItemStack`/`EntityDamageEvent` construction throws `No RegistryAccess implementation found` offline, and Mockito's typed `any(Location.class)` does not match the null location a mocked entity returns.
- Still **NOT_RELEASE_READY**, and no Paper server was launched. Which exact `EntityRemoveEvent.Cause` Paper emits when an item burns in lava is INFERRED, not measured; if it falls outside the allowlist the behaviour fails closed (a genuine burn goes unrecorded) rather than open. A controlled fixture for that case remains open. Evidence `docs/reviews/2026-09-14-item-loss-terminal-evidence.md`.

## 2026-09-12 — Custody counting: handovers, not events

- Candidate `5f34788d87467c74e328d7408cdd4082a9d24d0337bbcc3777210723bd9ed544`, full verification **483/483 PASS**, fixture `itemguard-lite-isolated-2757cc51e17a` returned `PASS_CONTROLLED_SMOKE` and is consumed.
- Counts genuine handovers instead of raw events: self drop/re-pick never counts, another player receiving counts once and both players stay in the chain, taking from a chest counts, storing/dying/hopper do not. `CustodyActionPolicy` is an allowlist so a new action cannot silently affect custody.
- **Derived, no schema change**: `CustodyChain` replays existing history rows, so there is no migration and it can never claim more than the database recorded.
- REAL DEFECT found only by running bots: reusing `anti-dupe.detection-cooldown-ms` as the anti-farm window produced `pingpong transfers=3` because that default is 5 seconds, shorter than one deliberate hand-over. Custody now owns `tracking.custody-window-ms`, default 15 minutes (`CustodyWindow`), with a regression test asserting the default outlasts a swap loop.
- Two-bot runtime evidence, cross-checked against the fixture SQLite rather than probe markers: 10 DROP + 10 PICKUP produced `self transfers=0 holders=1`, `transfer transfers=1 holders=2`, `pingpong transfers=1 holders=2`. New cases `custody-self-drop-not-counted`, `custody-handover-counted-once`, `custody-pingpong-throttled`, `custody-restore` all PASS.
- Two harness defects fixed (client-side bot repositioning rejected by movement checks; the server-side pull scanned for the drop entity before it existed). Fixtures `-f3862aab30ac`, `-7d5ef89c6f88`, `-add3f22f3e6a`, `-2e84fc35006d` are consumed FAILED and were not replayed. Evidence `docs/reviews/2026-09-12-custody-counting.md`.
- Presentation follows the privacy rule: counts are visible to the item's own viewer, holder names need `itemguard.history.others`. No small caps.
- Still **NOT_RELEASE_READY**: custody has no client visual acceptance; chest/death/hopper rules are unit-tested and allowlisted but only drop/pickup was exercised at runtime.

## 2026-09-12 — LITE GUI redesign: zones, guidance, navigation

- Candidate `6b119be549d3c548b3f935d8f958dd810046c21e40025e89cc32405b55c3e3da`, full verification **448/448 PASS**, fixture `itemguard-lite-isolated-3826b179b522` returned `PASS_CONTROLLED_SMOKE` and is consumed.
- GUI now reads as three zones after the BastionForge layout pattern: guide row (slot 4), a framed 28-slot content area that never touches the border, and a navigation row (previous 45, page 47, close 49, next 53; Back replaces previous on a timeline). Border and content background use different panes and every slot is painted.
- Disabled paging is a labelled control that states why; an empty browser opens a guided empty state pointing at `/ig check` instead of only a chat line; clicks route by named slot constants and stay cancelled.
- **ItemGuard does not use small caps** — explicit user instruction, an exception to the Genstycoon/HEOMC/BastionForge convention. A codepoint scan of `src/main/java/com/itemguard/lite` confirms zero small-caps characters, and `LiteMenuChromeTest` asserts it.
- New TDD coverage: `LiteMenuLayoutTest` 9, `LiteMenuChromeTest` 8, `LiteMenuRenderTest` 8, `LiteMenuNavigationTest` 5 (LITE suite 52/52). Runtime added `gui-guide-row`, `gui-close-button`, `gui-framed-border` alongside the existing GUI cases; inspect-before-click receipt recorded `slot 10, diamond_sword`.
- Two harness defects were found and fixed (probe asserted slot 0 was PAPER; bot expected a `LITE -` title and clicked slot 0). Fixture `itemguard-lite-isolated-4dbc4c4fc212` is consumed with `HARNESS_FAIL` and was not replayed. Evidence `docs/reviews/2026-09-12-lite-gui-redesign.md`.
- Still **NOT_RELEASE_READY**: client visual and Vietnamese acceptance by Thanh remain outstanding, as do crafting/natural-break, scale/concurrency/crash, other Paper versions and upload approval.

## 2026-09-12 — LITE L2 attempt 2: PASS_CONTROLLED_SMOKE

- Fixture `30_KET_QUA_THU_NGHIEM/itemguard-lite-isolated-15ba416c3eb8`, port 65300, candidate `b94170b2…78a67be5`, Paper 1.21.11 / Java 21. One attempt, two generations, one clean stop. **CONSUMED**, no replay. Fixture JAR hashes identically to `target/` after the run.
- Independent verifier: `PASS_CONTROLLED_SMOKE`. Generation 1 `permissions`/`identity` (`LITE_CODE BWTKJK`)/`history`/`gui-open`/`gui-click-readonly`/`duplicate-not-removed` all PASS; generation 2 `restart-identity`/`restart-history` PASS. Zero ERROR/exception/probe-fail; `All dimensions are saved` once per generation.
- Duplicate handling proven non-destructive on real Paper: `ITEMGUARD_DUPLICATE_CONFIRMED … locations=2 action=NOTIFY`, staff `[DUPE ALERT!]` received, both physical copies retained. GUI inspect-before-click receipt recorded the real rendered `diamond_sword` and the cursor stayed empty after clicking.
- First adjudication REJECTED on `member drilldown disclosed the other actor name`; raw transcript showed the only `LiteStaff` occurrence in the member stream was the vanilla `joined the game` broadcast, i.e. the assertion was too broad, not a plugin leak. Fixed by TDD with `verify.plugin_lines`/`verify.discloses_actor` plus `tools/lite-runtime/test_output_filter.py`; runtime tooling suite **14/14 PASS**, then the unchanged evidence passed.
- HONEST GAP: the redaction branch was NOT exercised at runtime. Fixture DB read-only inspection shows `tracked_items` still owned by `LiteStaff` and a single `SPAWN` history row, so the member's scoped query legitimately returned "No recorded history". Runtime therefore proves a member could not read another player's history at all here; the `another player (staff only)` / `location hidden` path remains unit-test-only (`LiteHistoryPrivacyTest`). The verifier assertion is now in place for the next run that reassigns ownership. SQLite `integrity_check` = `ok`.
- Cleanup verified: four owned children exit 0, none forced, PIDs 16552/13016/38808/34288 absent, port 65300 refuses connections.
- Evidence `docs/reviews/2026-09-12-lite-l2-attempt-2-pass.md`. Listing draft (NOT published, owner approval required) `docs/release/2026-09-12-lite-listing-draft.md`.
- Post-run full verification: 418/418 PASS again; a fresh LITE package produced `3df09110…f819a7` which `scripts/compare_jar_entries.py` proves byte-identical to the runtime-tested JAR at all 469 entries (whole-JAR hash differs only by ZIP metadata). Restoring `target/` to the exact executed bytes was BLOCKED at the tool-approval layer and was not retried; `b94170b2…78a67be5` inside the consumed fixture stays the only hash that was actually executed. P0 offline authority regression re-run PASS; runtime tooling 14/14 and artifact self-test 8/8 PASS.
- Still **NOT_RELEASE_READY**: pending real-client visual + Vietnamese acceptance, crafting/natural-break, scale/concurrency/crash, other Paper versions, release version rename and owner upload approval.

## 2026-09-12 — LITE L2 attempt 1: HARNESS_FAIL, fixture consumed

- Fixture `30_KET_QUA_THU_NGHIEM/itemguard-lite-isolated-3fa2f34b59de` ran exactly once on candidate `b94170b2…78a67be5`, port 64704, and is now **CONSUMED**. No replay; a further attempt needs fresh approval.
- Real Paper evidence obtained before the stop: LITE enabled as LITE edition, probe boot, `permissions`/`identity`/`history` cases PASS (`LITE_CODE RK6KHJ`), staff `/ig check` and `/ig search` worked, member `/ig search` denied, `/ig gui` opened. No server ERROR/exception.
- Stop cause was two **harness** defects, not product: `bots.cjs` inspect-before-click demanded `paper` in slot 0 while the LITE overview renders the real tracked material; `LiteProbe.java` compared the holder class to the abstract `LiteCommand$Preview` base instead of walking the superclass chain. Both fixed; duplicate/privacy/restart cases were never reached and are NOT claimed.
- Cleanup verified: both children exit 0, not forced, port 64704 refuses connections.
- Tooling now pins the current candidate, adds a member `/ig history` privacy step, and `verify.py` REJECTS any transcript disclosing the other actor's name/coordinates to a member — proven by a new contract test (7/7 PASS). Evidence `docs/reviews/2026-09-12-lite-l2-attempt-1-harness-fail.md`.

## 2026-09-12 — LITE L0/L1: rebuild, independent BLOCK, corrections

- Roadmap `.hermes/plans/2026-09-12_111742-itemguard-roadmap.md` executed through L0 and L1. LITE JAR `b94170b2681ed44e700d107b2fdb17456bed7f5021bcc95e520006c178a67be5`, source Full JAR `cce0e672557a21f869af8a887654f83ab55c35c11c434e3c26adad2d2fd74cce`, fresh clean package **418/418 PASS**.
- Independent read-only review of the earlier build returned **BLOCK**. All three blockers were reproduced from source and fixed RED→GREEN: (1) a member could read a previous holder's account name and exact coordinates because the owner-scoped window is keyed on the CURRENT holder while returning every retained event; foreign actor name/location are now withheld without `itemguard.history.others` while the event stays visible; (2) `docs/LITE_TESTING.md` advertised a stale SHA; (3) the artifact verifier printed the permission surface without asserting it and used a three-name denylist — now an allowlist with parsed permission defaults and alias checks.
- Accepted note also fixed: LITE automatic history deletion is now disabled in code (`ConfigManager.getCleanupIntervalHours`), not only by the shipped config. Docs now disclose PDC identity writes, inventory/container scanning and blanket craft cancellation.
- Evidence `docs/reviews/2026-09-12-lite-l0-l1-evidence.md`, frozen inputs `docs/reviews/2026-09-12-lite-input-manifest.json`. Superseded LITE hashes `88da1c3f…5d0c55` and `d86135e7…769a0de8` carry no evidence for this artifact.
- Still **NOT_RELEASE_READY**: no server started, no fixture staged/approved, no client visual or Vietnamese acceptance, no public upload/commit/push/deploy. Unfixed non-blocking notes remain: server-wide single in-flight query gate, inert `itemguard.search` without `history.others`, GUI page offset beyond the repository clamp, packager substring checks, and no binding of the Full JAR to the frozen source tree.

## P0 — Successor-35 authority design, offline only

- Fresh adversarial review closed the offline design contract: no-restricting-SID token predicate, `processKey` issuance/binding, pre-create collision semantics, dual-root enforcement receipt binding, journal-bound `runToken`, total recovery summary authority precheck, enforcement-gate scope, complete release tuple, guardian-owned stdin expansion, and one-shot authorization RED/GREEN coverage. Final narrow review passed the last mid-run journal-divergence summary closure.
- Status: `OFFLINE_TDD_NATIVE_DUAL_JOURNAL_PASS`. Host-only `NativeJournalLeaf` uses relative `NtCreateFile(FILE_CREATE)`, held native handles, write-through flushes, final-at-create SDDL, collision/path-traversal/reparse rejection. `NativeDualJournalWriter` composes separate disposable E:/C: leaves with the existing serial core; two exact streams were read back and independently accepted by `CommonJournalChain`. Native identity read-back now captures FILE_ID_INFO volume serial + all 16 file-ID bytes while the held append capability remains valid; a read-only reopen returns the same identity, append reopen after close is denied, and effective DACL inheritance is reported protected. Exact-root `GetVolumeInformationW` read-back now exposes a distinct volume-information serial, filesystem name and `FILE_PERSISTENT_ACLS`; its absence is rejected before native leaf creation. A host-only admission wrapper binds both non-normalized volume-information serials and both FILE_ID_INFO identities into an immutable in-memory receipt. GREEN is native-leaf 10 checks, identity/protection 8 checks, volume identity 3 checks, native-dual 10 checks, admission receipt 4 checks plus the full offline suite, with both scratch roots absent afterward. Observed RED: missing volume primitive, missing admission binding, earlier missing adapter, and invalid budget creating leaves before validation. The identity/protection assertions are fresh coverage. This is not an `ExecutionGuardian` entrypoint, durable `AUTHORITY_READY` journal record, sealed artifact, runtime namespace, Paper, release, deploy, or production action. Direct Claude review of the earlier native-leaf code timed out and the new direct review hit its max-turn limit; neither is review evidence.
- Current native extensions remain offline/host-only: `AUTHORITY_READY` dual-mirror genesis (8 checks), a one-process kill-on-close Job Object (3 checks), primary-token shape probe (3 checks), `CreateRestrictedToken(DISABLE_MAX_PRIVILEGE)` followed by exact Low MIC (`S-1-16-4096`) without restricting SIDs (4 checks), and a suspended-worker implementation that must assign its Job before `ResumeThread`. The restricted-token test was observed RED because `NativeRestrictedToken.cs` did not exist, then GREEN. On 2026-09-12, the exact suspended-worker loop failed at `AdjustTokenPrivileges(SeIncreaseQuotaPrivilege)` with `ERROR_NOT_ALL_ASSIGNED`, before `CreateProcessAsUserW`; the current account lacks that privilege. Do not grant it or substitute a different process API merely to bypass this gate. Fresh aggregate `offline-test.sh` passes (it intentionally excludes the privilege-dependent child-launch contract); both disposable scratch roots are absent. Process/thread DACL, identity verification and all runtime authority/recovery gates remain unimplemented.

## LITE — history/stats presentation fix, OFFLINE candidate

- Actual manual DB: 9 events / 3 codes, not demonstrated duplicate writes. Personal history/GUI now group by code in the recent 45 owner-scoped events; explicit-ID detail preserves every event and member scope.
- Parent full package: 414/414 tests PASS. New SHA `a2177873ec0b804abf51bb38c49aa4590cbd9196e18dd8474294c36e0adc03f5`. The history overview now uses one recognizable tracked identity per slot with retained action totals and click-to-timeline; `/ig stats` renders readable values instead of `PluginStats@...`. No live JAR replacement/reload/restart; runtime/visual evidence below applies only to older hashes.
- Evidence and limitations: `docs/reviews/lite-history-fix.md`. The manual session is closed after deadline with ItemGuard database close, saved worlds, controller/Java PIDs absent and port closed; visual acceptance remains pending. Details: `docs/reviews/lite-manual-active.md`.

## LITE — latest controlled smoke result (supersedes earlier NOT_ATTEMPTED status)

- User conditionally authorized assessing and running the separate LITE-only protocol. Exact candidate `d86135e750b31e9271ab08256c8717e42d26c84f999ef15a2535f5dc769a0de8` passed native Paper 1.21.11 build 131 / Java 21 smoke in `itemguard-lite-isolated-ed9e109ccc9a`.
- Independent verifier PASS: seeded ID/history, member/staff permissions, GUI click cancellation, duplicate NOTIFY without removal, unchanged identity/history after one clean restart. SQLite integrity_check `ok`.
- Both Java and both Node children exited cleanly; independent PID checks found none remaining and port 63411 refused connections. Fixture consumed; no replay.
- Still NOT_RELEASE_READY: real-client visual/VI acceptance, crafting/natural-break, scale/crash and other-version runtime remain unverified. Old guardian scope remains open. Evidence: `docs/reviews/lite-controlled-smoke-result.md`.

## 2026-09-11 — LITE shutdown correction / bounded review PASS

- H1 shutdown/recovery race reproduced in all three DB admission paths, fixed by atomic admission+enqueue against final close; accepted writes still drain. LITE DB diagnostics preserve causes; duplicate alerts now use declared itemguard.notify without changing Full's permission behavior.
- Fresh clean verify **403/403 PASS**, package checks and 226 application class byte comparisons PASS; JAR `target/ItemGuard-LITE-1.0.0-test.jar` SHA `d86135e750b31e9271ab08256c8717e42d26c84f999ef15a2535f5dc769a0de8`.
- Direct Claude Opus5 correction review + bounded notification/defaults follow-up PASS_FOR_OFFLINE_CANDIDATE; parent input hashes unchanged. Evidence `docs/reviews/2026-09-11-lite-shutdown-correction.md` supersedes the H1 blocker in earlier disposition, not historical raw outcomes.
- Still **NOT_RELEASE_READY**: user has now approved a fresh isolated LITE test and one stop/restart cycle, conditional on safety gates. NOT_STAGED/NOT_ATTEMPTED: prior full guardian/native execution admission remains incomplete; approval has not been consumed. See `docs/reviews/2026-09-11-lite-runtime-admission.md`. No Paper/gameplay/restart/stress/client verification or production/public upload. Old cross-version compile evidence is not runtime proof of this new JAR.

## 2026-09-11 — LITE offline candidate / compatibility compile audit

- Separate LITE entry point and read-only commands/preview; English default, Vietnamese optional, SQLite tracking and forced NOTIFY-only duplicate handling. No reclaim/lost-item/external-storage commands. Full internal classes remain packaged but are not registered as LITE commands. Shared namespace means Full and LITE must not be installed together.
- SQLite interrupted-transaction recovery now replaces the uncertain connection under the retained owner lock; failed recovery rejects queued/new work. Fresh clean verify: **397/397 PASS**, no skipped tests. Packager verifies every JAR entry; LITE SHA `dd4a32becc52cfbe3b1ec44cde83250d97a4c6d893f7ccfd8e77ed84d4654ece`.
- Artifact `target/ItemGuard-LITE-1.0.0-test.jar`; instructions `docs/LITE_TESTING.md`; exact evidence `docs/reviews/2026-09-11-lite-evidence.md`.
- Frozen current-source compile PASS against Paper API 1.21.4 (JDK21) and 26.2.build.123-stable (JDK25/release21). Canonical JAR still declares 1.21.11: **not** runtime support or an enabled older-version backport.
- **NOT_RELEASE_READY**. No fresh independent review, Paper/client/restart/stress verification, fixture authorization, public upload or production changes. P0/native authority and prior gameplay gates remain OPEN; no consumed fixture/receipt reused. `.hermes/WORKING_STATE.md` was read only; prior write timeout was not bypassed.

## 2026-09-09 — C3 retained observations, exact offline candidate

- Hồ sơ → Nơi đã quan sát → danh sách/chi tiết/back đã có read-only query có giới hạn; nhãn tách bản ghi tổng hợp, vị trí từng thời điểm, cùng/khác đợt không chứng minh bản sao đồng thời. Không suy custody/current holder/absence; pruning/partial/HavenBags gaps hiển thị rõ.
- Fresh Java21 378/378 PASS (52 catalog); Opus5 R2 + hai bounded followups PASS_FOR_OFFLINE_CANDIDATE, parent source/input hashes + 218 packaged class bytes verified. JAR `c8f95756adedb4a52c09908df0d43f90b7d64d9015ce081ff3b93501d7f6a534`; evidence `docs/reviews/2026-09-09-retained-observations-evidence.md`. P0 offline regression PASS, không đổi C# trong slice.
- C3 chỉ khép browser trên cache hiện có, KHÔNG đóng durable transfer/HavenBags/current holder. Không schema/writer/scan/adapter changes, không Paper/visual/deploy. Server/JAR mục tiêu chưa cung cấp. C1 contains-search scale, C2 provenance, C4 visual/shared admission UX, P0 native và P2–P6 vẫn OPEN. Checkpoint `.hermes/WORKING_STATE.md` không ghi lại/bypass timeout cũ.

## 2026-09-09 — P0 pure dual-commit core; C2/C3 decision boundary

- DualJournalCommitCore đã TDD + final 60 pure checks, full offline suites PASS; Opus5 initial+bounded followup PASS_FOR_OFFLINE_CANDIDATE. Parent exact manifests/binary rerun PASS. Evidence `docs/reviews/2026-09-09-dual-journal-commit-evidence.md`. Không real journal file/durable I/O, không host/ACL/token/job/runtime authorization; P0 vẫn OPEN.
- Research HavenBags pinned source cho thấy loaded-cache mutable lists/full collection getters và close event sau dirty-cache update, không durable transfer/all-storage absence. `docs/research/2026-09-09-havenbags-custody-contract-audit.md` có evidence paths/lines và proposed contract. Cần duyệt durable observation/provenance/custody design, server/artifact mục tiêu trước adapter/schema; không hỏi lại tên HavenBags đã biết. BastionForge bridge tiếp tục DEFERRED theo quyết định 08/09.
- Không dừng vì hai audit agents timeout: parent đã tự trace source và hoàn thiện phần offline trên. Java candidate vẫn bản P1a dưới đây (364/364, không rebuild cho C#-only). C1 contains-search scale, P0 native authority, C4/P1 runtime, P2–P6 vẫn chưa hoàn tất. Roadmap checklist chỉ tick các phần có evidence, không đóng cả C1/P1/P0.

## 2026-09-09 — P1a generator/service collision, offline candidate

- Service đã nối PublicItemCodeGenerator/SecureRandom, callback lỗi không giữ khóa nguồn mãi, lore-position âm không chặn publication. Không rewrite identity/schema. Seven real service+JDBC cases gồm collision/exhaustion/concurrent sources, stale source/generic error, publication outage và reopen reconciliation; Bukkit/Paper vẫn mocked, reopen DB không phải restart Paper.
- Fresh full Java21 364/364 PASS; Opus5 initial và bounded followup PASS_FOR_OFFLINE_CANDIDATE; exact reviewed hashes và 216 JAR classes verified. JAR `b04dafda4e9525d70c0c20df0daf2fd228c5588363b58f5e442573ea97749644`; evidence `docs/reviews/2026-09-09-p1a-generator-service-evidence.md`. Mockito TEST-only không đóng gói; SQLite native có trong JAR.
- P1 runtime/NBT/natural-break vẫn OPEN; C1 text-scale, C2 provenance, C3 custody/HavenBags, C4 visual, P0 real host và P2–P6 chưa khép. Không Paper/deploy/restart/commit/push; không reuse consumed fixture. `.hermes/WORKING_STATE.md` giữ nguyên lần ghi timeout, không bypass.

## 2026-09-09 — C1 index/retry implementation, offline verification

- Schema 8 additive history index và failure-safe catalog paging đã triển khai. Final 351/351 Java tests PASS (38 catalog, 9 migration); JAR `179606b04b25868ed0d199ad1722dd6db7215d6908772c284f42b9a129cbe84a`, 216 packaged class bytes verified. Opus5 implementation conditional PASS + bounded disposition followup PASS_FOR_OFFLINE_CANDIDATE; parent exact source/JAR checks PASS. Không broad re-review hoặc runtime approval.
- Ba DB synthetic hơn 1M events mỗi DB: migration/index exact-result/reopen PASS, 60 history queries đúng identity/thứ tự, mixed 30 writes + 30 reads và retention count PASS mỗi DB. General contains-text search trên 100k records vẫn bị budget ngắt; phần search-scale chưa khép.
- Evidence `docs/reviews/2026-09-09-catalog-c1-evidence.md`; design `docs/design/2026-09-09-catalog-c1-index-pagination.md`. Không Paper/client/production, không đổi giới hạn query để lấy xanh, không deploy/restart. Schema 8 không downgrade bằng stamp; live cần backup/maintenance/restore approval riêng.
- `.hermes/WORKING_STATE.md` không retry/bypass lần ghi bị timeout. Roadmap P0–P6/C2–C4 còn mở; mục dưới là lịch sử, không đại diện artifact mới.

## 2026-09-08 — Catalog A offline candidate (mới hơn baseline runtime bên dưới)

- `/ig browser` đã có catalog read-only, search/filter/material category/profile/retained history; `/ig browser <player>` giữ đường cũ. User chưa visual acceptance. Nguồn plugin/HavenBags/custody chưa hỗ trợ và GUI nêu rõ.
- Fresh Java21 `clean test package`: 334/334 PASS (30 catalog). JAR SHA `0c86700e863e7835951e277d847b8c089d270eb5fba3a9fc775a1bdb0d07aaed`. Opus5 R2+disposition: PASS_FOR_OFFLINE_CANDIDATE. Evidence: `docs/reviews/2026-09-08-catalog-implementation-evidence.md`, `catalog-final-manifest.json`.
- Blocker trước live: 1M synthetic history rows trên schema hiện tại gây SQLITE_INTERRUPT; scratch-only composite index experiment đọc đúng 100 rows. Chưa product migration/index. Paper/client/chat/inter-plugin runtime chưa kiểm chứng.
- `NOT RELEASE READY`; baseline runtime/receipt bên dưới không đại diện local JAR mới. Không production/deploy/restart/commit/push, không reuse consumed fixtures. Ghi `.hermes/WORKING_STATE.md` đã bị approval timeout; không retry/bypass.

Cập nhật: 2026-09-01

## Kết luận hiện tại

`NOT RELEASE READY`. Current candidate vẫn `2e1fb7d8…adc5`, Java 21 `292/292`; các controlled block-container/hopper/SQLite/stackable/craft/multi-source/UX gates trước giữ nguyên. Natural break vẫn `NOT_ISSUED`: Attempt 12 đã đóng factual client-load/Paper/Bukkit path tới `CONTENT_ENTITY_ADD`, nhưng fresh-identity seam còn inconclusive. Successor Attempt 14 có execution history `NOT_ESTABLISHED`: bundle 34 mất đúng 45 payload manifest có metadata Bitdefender khớp path+SHA; current receipts không có runtime activation/trigger/Paper log/verdict, post-state clone/JAR/DB khớp baseline, ports đóng, nhưng không có sealed contemporaneous journal để loại trừ hậu-xóa hoặc transient launch. Attempt 14 được consume fail-closed, bundle 34 không còn authorize future execution. Production chưa deploy/restart/verify.

## Baseline

- `OBSERVED` Repo: `https://github.com/thanhredfield1999/ItemGuard.git`.
- `OBSERVED` Target trong `pom.xml`: Java 21, Paper API `1.21.11-R0.1-SNAPSHOT`.
- `OBSERVED` Head khi bắt đầu: `1b7d055 fix: async inventory error, item lore display, back button navigation`.
- `OBSERVED` GitHub CLI đã đăng nhập tài khoản `thanhredfield1999` và remote fetch/push đúng repo.
- `OBSERVED` Notion project: `https://app.notion.com/p/ItemGuard-3c52c2fbb3ad8167bf88c3a77b0beffb`.
- `OBSERVED` Notion task: `https://app.notion.com/p/ItemGuard-Stabilize-identity-anti-dupe-and-persistence-baseline-3c52c2fbb3ad81e9b0d4d2445ddf4dd3`.

## Verification gần nhất

- `OBSERVED scaffold incidents / product NOT ISSUED`: Attempt 13 fail trước Paper do `seal_owner_resistant_tree` bắt buộc descendant directory trên valid files-only preflight tree và ném `StopIteration`; successor đã phủ files-only offline (`11`, `10`, directory control `11`, current contracts `66/66`, static `380`) nhưng không tạo runtime/product PASS. `review-bundle-attempt-34` sau invocation Attempt 14 hiện còn `235` files và thiếu đúng `45` payload; read-only Bitdefender audit đối chiếu `45/45` metadata `Atc4.Detection` với exact path và manifest SHA, zero unexplained missing. Attempt 14 không có runtime activation/trigger/Paper log/verdict/classification; post-state original/DB/runtime seal/ports exact, nhưng không có sealed contemporaneous journal để loại trừ hậu-xóa hoặc transient launch, nên classification giữ `PAPER_EXECUTION_NOT_ESTABLISHED_BUNDLE_INTEGRITY_LOSS`, không phải `FAIL_BEFORE_PAPER`. Antivirus process-termination causality vẫn `INCONCLUSIVE`. Không restore/whitelist quarantine, không rerun attempt 13/14, không sửa product. Incident: `docs/incidents/2026-09-01-natural-break-evidence-scaffold-preflight-and-bundle-integrity.md`; evidence: `docs/evidence/2026-09-01-natural-break-attempt-14-forensic-classification.json`; report: `docs/runtime/2026-09-01-natural-break-attempt-14-execution-not-established-bundle-integrity-loss.md`.
- `VERIFIED factual client-load + Paper/Bukkit natural-break path through content entity add / fresh identity INCONCLUSIVE / product NOT ISSUED`: immutable bundle Attempt 18 manifest `59923469…fe611`, tree `513726e2…dfa93`, exact set `177+manifest`, independent Hermes reviewer `deleg_3b86d32b` literal PASS chỉ cho consumed `attempt-12`; receipt SHA `68e72fef…6341`. Runtime token `67af1b39-...`: exact `player_loaded` `0x2b/1 byte`, factual `CLIENT_LOADED(timeout=false,preArm=0)`, JDI `PLAYER_ACTION_ENTRY→IMMOBILE_PASS→CLIENT_LOADED_PASS→...→GAME_MODE_RANGE_PASS`, START/STOP `577338485/486`, client `LOCAL_RESOLVED`, uncancelled Bukkit interact/damage/break, exact one content drop/entity-add. Probe terminal `fresh-identity-timeout` sau `59.889s`; fixture không ghi terminal entity validity, ItemListener/request outcome, publication state/detail hoặc stopped DB/NBT nên product defect không được phát hành. Operation FAIL; automatic restore FAIL do read-only attribute propagation, one recovery restore PASS sau Paper exit; operational JAR/probe/DB byte+logical exact, integrity `ok`, trigger/locks 0, ports đóng; production untouched. Attempt 12 sealed `39 payload + manifest`, manifest `04d5121d…27d80`, tree `6d2aa345…95e687`. Evidence: `attempt-12/classification.json`; report: `docs/runtime/2026-08-30-controlled-natural-break-factual-client-load-fresh-identity-inconclusive.md`.
- `VERIFIED controlled Paper server branch / product verdict NOT ISSUED`: immutable bundle attempt 15 manifest `c9fd5aab…484d0`, exact set `124+manifest`, contracts `18,38,20,23,11,10,15,20,29,22,12`, static `177/177`, dynamic sequence `20/20`; independent reviewer `deleg_a1634e20` PASS chỉ cho consumed `attempt-11`, exact set trước/sau `125/125`. Runtime token `60e5de7b-...`: exact START seq `806203873` tới `ServerGamePacketListenerImpl.handlePlayerAction` trên `Server thread`; JDI chain `PLAYER_ACTION_ENTRY→IMMOBILE_PASS→CLIENT_NOT_LOADED_RETURN`, strict verdict `REJECTED_CLIENT_NOT_LOADED`. START sau `ACTOR_READY` `52ms`; STOP seq `806203874` sau `3,770ms`, ACK STOP sau `37ms`; zero Bukkit interact/damage/break. Paper `handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket)` mở client-loaded gate; protocol 1.21.11 có `player_loaded` `0x2b`, pinned Mineflayer `4.37.1` / minecraft-protocol `1.66.2` không có implementation gửi packet này. Classification `HARNESS_CLIENT_LOAD_HANDSHAKE_GAP`; không gán ItemGuard/WorldGuard/dependency defect. Operation FAIL, restore PASS, ports đóng/triggers 0, operational JAR/probe/DB byte+logical match baseline; production untouched. Evidence: `E:\AI.WORK\evidence\itemguard-natural-container-break-telemetry-20260829\attempt-11\classification.json`; report: `docs/runtime/2026-08-30-controlled-natural-break-client-load-guard.md`.
- `INCONCLUSIVE controlled natural container break packet boundary / product verdict NOT ISSUED`: immutable bundle attempt 14 manifest `79c889d0…dcd7f`, exact set `116+manifest`, full contracts `18,29,20,23,11,10,15,20,27`, static `172/172`, dynamic packet sequence `20/20`; independent reviewer `deleg_d23d4707` PASS chỉ cho `attempt-10`, exact set trước/sau `117/117`. Runtime token `93e7b819-...`: probe `PREPARE_ARMED→RESERVE_CONFIRMED→ACTOR_READY`; bot `CLIENT_READY→DIG_STARTED→SERIALIZED START(seq 864550997)→SERIALIZED STOP(seq 864550998)→ACK(seq 864550998)→LOCAL_RESOLVED`. START không có ACK riêng trong 3,765ms; STOP ACK sau 14ms. Zero `INTERACT_EVENT/INTERACT_BLOCKED/DAMAGE_EVENT/DAMAGE_CANCELLED/BREAK_EVENT`, marker timeout. Exact Paper ACK có thể nằm ở pre-handler chunk/range rejection hoặc sau `handleBlockBreakAction`; chưa phân biệt branch. Classification `INCONCLUSIVE_SERVER_PLAYER_ACTION_GUARD_OR_HANDLER_SEAM`, refined timeline `INCONCLUSIVE_START_TRANSPORT_OR_EARLY_SERVER_GUARD`; không gán WorldGuard/dependency cancellation/ItemGuard defect. Operation FAIL, restore PASS, ports đóng/triggers 0, operational JAR/DB byte+logical baseline. Evidence: `E:\AI.WORK\evidence\itemguard-natural-container-break-telemetry-20260829\attempt-10\classification.json`; report: `docs/runtime/2026-08-30-controlled-natural-break-packet-boundary-inconclusive.md`.
- `INCONCLUSIVE controlled natural container break / product verdict NOT ISSUED`: immutable bundle attempt 10 manifest `2c1e776d…34d1`, exact set `85+manifest`, contracts `18/18,20/20,20/20,23/23,11/11,10/10,15/15,review-gate 20/20`, static `124/124`; independent reviewer `deleg_c61a5e8a` PASS chỉ cho `attempt-6`. Runtime token `1d994074-...`: probe `PREPARE_ARMED→RESERVE_CONFIRMED→ACTOR_READY`; bot `CLIENT_READY→DIG_STARTED→DIG_CLIENT_TERMINAL(LOCAL_RESOLVED)`; zero `INTERACT_EVENT/INTERACT_BLOCKED/DAMAGE_EVENT/DAMAGE_CANCELLED`, marker timeout. Classification `INCONCLUSIVE_PRE_INTERACT_PACKET_SEAM`; không gán WorldGuard/dependency cancellation và không xác nhận product defect. Operation FAIL, restore PASS với ports đóng, trigger 0, operational JAR/DB byte+logical baseline. Evidence: `E:\AI.WORK\evidence\itemguard-natural-container-break-telemetry-20260829\attempt-6\classification.json`.
- `INCONCLUSIVE controlled natural container break / product verdict NOT ISSUED`: exact candidate `2e1fb7d8…adc5`, clone-only probe `f26fb36a…466a`, Paper `1.21.11-131`. Immutable bundle attempt 5 có manifest `b3007469…50cb`, exact set `37+manifest`, static `67/67`, fixture `21/21`, extra-file negative PASS và independent fallback `PASS_FOR_CONTROLLED_PAPER`. Runtime attempts 1–3 lần lượt fail trước product event vì missing legacy token, Mineflayer enchant compatibility và client chunk-stream race; mỗi attempt restore exact baseline PASS. Attempt 4 qua probe enable, `/prepare`, reserve `NOW`, client exact-chest readiness và clean shutdown, nhưng final marker timeout; fixture không persist receipts riêng cho dig start/resolve, `BlockBreakEvent`, `BlockDropItemEvent`, entity-add hoặc fresh identity nên evidence không định vị được boundary. Không chạy stopped DB/NBT seal, restart hoặc cleanup journey; không xác nhận production defect. Restore byte/logical exact, integrity `ok`, zero trigger/sidecar, ports đóng. Runtime: `docs/runtime/2026-08-29-controlled-natural-container-break-inconclusive.md`.
- `VERIFIED source/build + controlled double-chest chunk-border authority`: exact candidate `2e1fb7d8…adc5`, probe `fb49fcdc…80a9`, Paper `1.21.11-131`, token `97b0a524-...`. Real logical `DoubleChestInventory` event từ exact hopper ánh xạ raw slot sang physical source half/local3; cả source+sibling nhận real unload event và đều unloaded trong reserve. Old `9VGRD4` thành `ABORTED/SOURCE_CHANGED`; stopped NBT giữ one untagged sword tại source/local3, sibling/hopper empty. Reload cả hai chunk với real load events; retry fresh `H0FBX9` `PUBLISHED`, restart giữ exact identity/SHA, cleanup one item/three blocks và operational restore byte/logical exact. Claude provider không có verdict do HTTP 400 quota; independent fallback review attempt 7 `PASS_FOR_CONTROLLED_PAPER`. Independent stopped-evidence verifier `143/143` PASS; archive `8383ccf0…9a9a` (`67` members read-back verified). Runtime: `docs/runtime/2026-08-29-controlled-double-chest-chunk-border.md`.
- `VERIFIED source/build + controlled block-container physical replacement`: exact candidate `2e1fb7d8…adc5`, probe `bfa51268…9294`, Paper `1.21.11-131`, token `3ce2a813-...`. Trong active first-publication reserve, fixture clear original slot rồi thay cùng tọa độ `CHEST→BARREL→CHEST`, deserialize exact same bytes vào local slot `3`; chunk loaded, source key/digest giữ nguyên. First `MN9NS9` `ABORTED/SOURCE_CHANGED`; offline NBT one untagged sword/zero identity/hopper empty; retry distinct `KJOMHK` `PUBLISHED`; restart/cleanup/restore PASS. Exact `cc/claude-opus-4-8` review blockers `[]`; independent verifier chạy qua đúng model route và parent rerun `199/199` PASS. Attempt 1 chỉ transport harness failure, zero phase. Runtime: `docs/runtime/2026-08-28-controlled-block-container-replacement.md`.
- `VERIFIED source/TDD/build + controlled block-container pre-write chunk unload`: candidate `2e1fb7d8717a02b316bef9c017d09d67b8705c694519433276bd6e316d5adc5e`, probe `59cbd042ed5d0bd25a1912c5393259022ad34007461912fc58b8968d49676f3d`, Paper `1.21.11-131`, token `02502468-4456-4f73-acfb-8d2b37123488`. Captured inventory cũ có thể mất physical authority sau unload; correction dùng loaded-only live re-resolution trước match/write, không force-load, giữ exact chest half/local slot và đóng generic public bypass. Ba RED contracts; focused `28/28`, full Java 21 `292/292`; exact Opus 4.8 `PASS_FOR_CONTROLLED_PAPER`, blockers rỗng. Real untagged hopper attempt cancel `1/1`; row `3VZ5IG` được tạo trước unload và hoàn tất `ABORTED/SOURCE_CHANGED` sau real `ChunkUnloadEvent`. Offline NBT giữ exact untagged sword chest slot3/hopper empty. Real reload/retry cùng source key tạo distinct `NABXUS`, canonical/snapshot/PUBLISHED `+1/+1/+1`; restart/cleanup giữ exact SHA/DB. Attempt 1 là non-authoritative CPython parser mismatch, restore PASS; attempt 2 authoritative independent verifier `56/56` PASS. Runtime: `docs/runtime/2026-08-28-controlled-block-container-prewrite-chunk-unload.md`; incident: `docs/incidents/2026-08-28-block-container-stale-inventory-after-chunk-unload.md`.
- `VERIFIED source/unit/build + controlled same-tick double-hopper concurrency`: candidate `fea0b09cd6dbfffabcdca1a057cee352317535f8f52f982cdf00c00a4846b15e`, probe `9d06e0d9c205e91196f0d959c5538e95e65a250efc857923c86b6380df441e36`, Paper `1.21.11-131`, token `3fdcf034-b8da-4e0e-8813-db569e9e6496`. Characterization distinct-source/reversed-completion PASS ngay, không có production fix; focused `23/23`, full Java 21 `289/289`, Opus 4.8 `PASS_FOR_CONTROLLED_PAPER`, blockers rỗng. Hai initiators cùng tick `48`; untagged cancel `1/1` mỗi hopper, tagged ALLOW `1` mỗi hopper. Exact `DR5YM0` và `T122UA` map tới hai physical chest blocks/local0, DB canonical/snapshot/PUBLISHED `+2/+2/+2`, zero unwanted delta; restart/cleanup giữ logical state. Attempt 1 runtime PASS nhưng non-authoritative vì thiếu preserved row payload; attempt 2 authoritative independent verifier PASS. Operational allowlist restore byte/logical exact. Archive `81b238a5…4a25` (`73` members read-back verified). Runtime: `docs/runtime/2026-08-28-controlled-double-hopper-same-tick-concurrency.md`.
- `VERIFIED source/unit/build + controlled hopper topology fail-closed`: candidate `0fa1e247c405cb5022e4cfedcbcc037ba61134bf4af008579460e0f63a592327`, probe `664f1f9405d55a6d91714f2b92b67ee270b8f8aa7705dbf49d8da5f1eecb945b`, Paper `1.21.11-131`, token `3d34a4ae-0875-427f-934c-5648a55237f9`. Focused `21/21`, full Java `288/288`, Opus 4.8 `PASS_FOR_CONTROLLED_PAPER`. Positive double-chest right half/local3 → block hopper → destination double chest: untagged cancel `1/1`, tagged ALLOW source/destination `1/1`, exact PUBLISHED source key/canonical/snapshot `+1`. Block→HopperMinecart và HopperMinecart→block đều fresh eligible `1/1` cancel + tagged `1/1` cancel, item giữ nguồn; StorageMinecart/virtual/partial adapters fail-closed, ineligible control ALLOW. Hai negative phases, restart và cleanup giữ exact post-positive DB counts/logical SHA; history/observation/finding/stat/claim zero-delta. Attempt 1/2 là harness `NOT_RUN`, attempt 3 authoritative PASS; operational allowlist restore byte-exact. Archive `65e8cee7…cd935` (`77` files read-back verified). Runtime: `docs/runtime/2026-08-28-controlled-hopper-topology-fail-closed.md`; incident: `docs/incidents/2026-08-28-unsupported-hopper-topology-false-allow.md`.
- `VERIFIED source/unit/build + controlled same-host cooperative SQLite single-owner`: candidate `a6dafcf11644712732c1336ef36aa640d59bb37d704c3211ae9a06770fe81907`, Paper `1.21.11-131`, token `1c24dace-368b-4214-bc1f-710363e937bc`. Tám RED defects ownership/init/close/release/callback đã TDD-correct; focused persistence `36/36`, full Java 21 `283/283`, final lifecycle repeat `10/10`; exact Opus 4.8 `PASS_FOR_AUTHORITATIVE_RERUN`, blockers rỗng. Runtime exactly-one owner; loser/standalone/second Paper fail-fast `already owned`, zero `SQLITE_BUSY`; clean/force-kill handoff reacquire/integrity `ok`; DB byte/logical/count zero-delta; Win32 identity một physical file; clone restore byte-exact. Archive `96310ba4…26ef` (`117` files independently verified). Scope chỉ local fixed NTFS + cooperating exact jars; multi-writer/multi-server/network/shared filesystem vẫn unsupported/not verified. Runtime: `docs/runtime/2026-08-28-controlled-sqlite-single-owner-fail-closed.md`; incident: `docs/incidents/2026-08-28-sqlite-multiple-owner-contention.md`.
- `VERIFIED source/unit/build + controlled multi-source custom-stack merge`: runtime candidate `8c2ebefa203e13577ab72977be3bf29642d1089414a0e9d9e3a5a135d26a7cf3`, probe `a1ec8342949e64e4e16acdc5878907b5893210cc6335bc3939d896644f4b9a93`, Paper `1.21.11-131`, token `9dfa334c-a9a4-4cab-9d3d-83abd12f326c`. Spawn `34 entity/37 item`; exact state `29/37`; untagged control `5→1`, COMPLETE/code-only/UUID-only/malformed/wrong-type/mixed-amount identities không coalesce, cả source/target event endpoints được phủ, component mismatch zero-event control, restart và cleanup exact. Runtime attempt đầu đã xác nhận defect `IntTag -> get(STRING)` làm handler throw và merge bypass; TDD correction phân loại wrong datatype `CORRUPT` trước typed read. Focused `21/21`, full Java 21 `269/269`, probe build/diff-check PASS; exact Opus 4.8 runtime correction `PASS_FOR_CONTROLLED_RERUN`. Evidence archive `cfa002a5…62c4`; report `docs/runtime/2026-08-27-controlled-multisource-custom-stack-merge.md`; incident `docs/incidents/2026-08-27-wrong-pdc-type-ground-merge-bypass.md`.
- `VERIFIED source/unit/build + controlled command/GUI UX UAT`: candidate `06cf01c7c589545838b29833ed158b31954f850d22e080750179e9b60597b037`; focused lifecycle/meta delta `12/12`, full Java 21 `266/266`, `git diff --check` PASS; exact Opus 4.8 full review + meta-null correction đều `PASS`, production ready `NO`. Authoritative rerun: member `d26d730e-6bff-4471-8c96-5d104d811f62` `24/24 PASS`; staff `40698a9d-a0ff-4422-ac9a-3b5e21a1a33b` `38/38 PASS`, exact fixture `29` item / pagination `28+1`. BotChecker full gate trước rerun: `318 pass / 0 fail / 2 intentional Windows skips`, build/diff-check PASS; runtime source fingerprint `fc7d4d7e…2a10`. Evidence archive `b64914fe…9a00`; runtime doc `docs/runtime/2026-08-27-controlled-command-gui-ux-uat-rerun.md`. Historical PARTIAL/RED reports vẫn được bảo tồn, không rewrite.

Command:

```text
JAVA_HOME=C:\Program Files\Java\jdk-21
.\mvnw.cmd clean test package --no-transfer-progress
```

Kết quả mới nhất (2026-08-27): focused ground-merge/craft/hopper/stackable suite `24/24` PASS; full `BUILD SUCCESS`, `237/237` tests, 0 failures, 0 errors. Controlled ground-merge runtime dùng exact JAR SHA-256 `5fc2512f57fb4c5d35e6fbac3a889b3263945340d790f9ccb0d26d05133b8e3c`; clean rebuild `c558b38704b173c86991bac1e1bb5954943b3636fef62794f7ea6e9a66e6d2a7`, với `343/343` non-manifest entries byte-identical và zero differing entries; `target` đã được khôi phục về exact runtime bytes.

- `VERIFIED unit/build`: Maven Wrapper pin Maven `3.9.16`, chạy bằng Java `21.0.4`.
- `VERIFIED unit`: SQLite in-memory create/insert/query qua Xerial `3.53.2.1`.
- `HISTORICAL verified hopper baseline`: candidate rebuilt `258231e0dffe9d1a6048254fa9efa84ca1b9ef7ee6bae4ce1d66b6da8158b641` từng qua Java 21 `224/224`, focused hopper/double-chest neighbouring `16/16`; hopper runtime dùng whole-JAR `1ef91c0c…3509`, với `408/408` non-manifest entries byte-identical. Dòng này chỉ giữ attribution lịch sử, không phải current candidate; current hopper-topology candidate là `0fa1e247…2327` ở trên.
- `VERIFIED controlled stackable fail-closed`: run `9284f975-9ce1-4ece-9436-7473fca03c33`; clone cố ý bật `track-stackable=true`, fresh `APPLE x2` vẫn không được tag và real split tạo hai x1 untagged. Exact legacy `WOZF78/7904f9e8-...` được restore từ old snapshot, real click bị cancel, readiness false và trạng thái giữ qua clean restart. Exact legacy DB rows bằng baseline; mọi counts zero delta; integrity `ok`; cleanup removed=3. Final exact `cc/claude-opus-4-8` `PASS`. Runtime: `docs/runtime/2026-08-26-controlled-stackable-identity-fail-closed.md`.
- `VERIFIED controlled two-online-player exact identity`: run `af9af793-1bac-4261-b266-9535ad7e3120`; production publish `98IA43/ad82304e-...` một lần, probe clone exact SHA `225860d3…ae21b` sang player UUID thứ hai. Atomic capture khóa đúng hai completed `PLAYER` observations cùng epoch `1787733435319`, holder UUID khác nhau, slot 12, một `CONFIRMED/NOTIFY`; finding/stats `+1/+1`, one attributable `SPAWN`, no PREPARED/ABORTED/claim/trigger delta. Restart giữ hai physical copies; cooldown giữ finding/stats; cleanup removed=2 và observations về zero. Final exact Opus 4.8 `PASS`. Runtime: `docs/runtime/2026-08-26-controlled-two-player-exact-identity-anti-dupe.md`.
- `VERIFIED controlled real double chest physical source`: run `98b434b0-fecc-4ee6-8c3b-61ab583fbc06`; scanner-disabled click phase ghi server click `1/1` tại raw slots `3/30`, publish distinct identities vào exact physical halves `(9,-60,-2)/(10,-60,-2)`, local slot3. Exact DB delta tracked/snapshot/PUBLISHED/SPAWN `+2/+2/+2/+2`, zero observation/finding/stat/reclaim delta. Separate scanner-enabled startup atomically capture completed epoch `1787738657367` với đúng hai `CONTAINER` holders/local3, no finding; clean restart/cleanup PASS. Initial Opus review `BLOCK`, correction và final exact Opus 4.8 `PASS`. Runtime: `docs/runtime/2026-08-26-controlled-double-chest-physical-source.md`.
- `VERIFIED controlled same exact identity in double chest`: run `97353438-bfd4-43d3-bbe3-daf894757d39`; real InventoryOpen production path first-publish một left/local3 identity `MK2BR2/2d39fd99-...`, probe clone exact SHA `57d8a29e…a293` sang right/local3. Publication/canonical/snapshot/SPAWN `+1`; right publication zero. Production scheduler tạo finding ID4 tại epoch `1787741743307`, đúng two completed `CONTAINER` holders/local3, `CONFIRMED/NOTIFY`, finding/stat `+1`. Restart epoch `1787741854276` có same two rows nhưng exact finding ID4/stat unchanged (positive cooldown proof); cleanup removed items=2/blocks=2, observations zero. Initial Opus4.8 `BLOCK`; corrected/final approved Opus4.6 fallback `PASS`. Runtime: `docs/runtime/2026-08-26-controlled-same-identity-double-chest-anti-dupe.md`.
- `VERIFIED controlled real hopper first-publication transfer`: source defect cho phép eligible untagged retry đi qua holder cooldown đã được sửa fail-closed bằng unconditional cancellation + cooldown-only next-tick source scan. Authoritative run `c1331bad-149f-4da8-9f36-55fdd9c18986`, corrected probe `d5eb8a81…165cb1`: untagged source `1` attempt/`1` cancelled/`0` allowed; tagged source/destination exact `1/1`; actual PUBLISHED row source single chest `(2,-59,7)/local4`, owner null, exact history empty; restart epoch `1787748430177` chỉ có destination `(3,-60,7)/slot0`, no finding/stat; cleanup one item/three blocks, observations zero. Historical run `c684074c-...` giữ `BLOCK`, không authoritative. Final exact Opus 4.8 `PASS`, required fixes none, production change `NO`. Runtime: `docs/runtime/2026-08-26-controlled-hopper-transfer-publication.md`.
- `VERIFIED controlled craft output fail-closed + FINAL OPUS PASS`: run `33c4beab-e610-40ae-9f06-03550a84a87f`; real result-slot normal mode0 và shift mode1 đều event/cancel exact `1/1`, matrix giữ `1/3` nuggets, sword0, exact CraftListener denials2. Restart giữ nuggets3/sword0 + persisted exact table `(-3,-60,5)`; cleanup nuggets3/swords0/table1/location artifact. DB hash/count map exact baseline qua mọi phase. Runtime candidate `763ec6c…a667`, rebuilt `d8a0919f…5cec`, `382/382` entries identical, full `232/232`. Listener ownership defect được RED→GREEN fix. Final exact `cc/claude-opus-4-8` `PASS`, blockers/fixes rỗng; clarification xác nhận gate approved, không cần candidate change, production chỉ nhận fix sau deployment được phê duyệt riêng. Runtime: `docs/runtime/2026-08-26-controlled-craft-output-fail-closed.md`.
- `VERIFIED controlled ground-item custom-stack merge + FINAL OPUS PASS`: authoritative run `371e9cc0-2bb7-4c7c-ad89-bab2ce25063f`; identical control `MINECART x1/max2` phát đúng `1` event, initially/final cancelled `0/0`, merge thành one `x2/max2`. Protected pair phát `8` attempts, initially cancelled `0`, final cancelled `8`, giữ exact two `x1` UUIDs; control survivor và protected pair giữ exact UUID qua clean restart. DB logical/file SHA và mọi domain counts exact baseline qua prepare/restart/cleanup; cleanup removed entities/items `3/4`, offline NBT `4` region files / `34` chunks zero exact entity/code/item-UUID hit. Initial token `f03281a4-...` là harness-only RED do Paper mergeability sentinel, được RCA/seal/archive riêng rồi TDD-correct và rerun fresh token. Candidate `5fc2512f…8e3c`, probe `0fd75b88…5e03`, clean rebuild `343/343` entries identical; focused `24/24`, full `237/237`. Final exact `cc/claude-opus-4-8` `PASS`, blockers/fixes rỗng, production change `NO`, release ready `NO`. Final archive `5e7765f8…a1be` (`34` files, read-back verified). Runtime: `docs/runtime/2026-08-27-controlled-ground-item-custom-stack-merge-gate.md`; review: `docs/reviews/2026-08-27-ground-item-custom-stack-merge-review.md`.
- `VERIFIED independent source-loss review`: exact `cc/claude-opus-4-8` vòng 4 trả `VERDICT: PASS` cho current hash. Vòng 1–3 BLOCK đã dẫn tới full-receipt single-flight, xóa unsafe relocation authority, exact-source regressions và player scan không phụ thuộc container gate. Review: `docs/reviews/2026-08-24-source-loss-opus-review.md`.
- `VERIFIED controlled player-slot source-loss`: production first-tag tạo PREPARED; probe lưu exact bytes, xóa slot 8 và persist playerdata; force-kill/restart không source giữ PREPARED và canonical/snapshot `0/0`; reclaim deny unknown/no issuance. Exact same-slot restore + anchored scan tạo đúng một PUBLISHED/canonical/snapshot; publication/snapshot/physical SHA cùng `0de6df99…25a07`. Runtime: `docs/runtime/2026-08-24-controlled-player-slot-source-loss-reconciliation.md`.
- `VERIFIED controlled predecessor attribution`: `0bb8d23b47eea7b10cb40c8db02a392922a924ef660e20b4154b3f2972f8c525` là exact single-chest runtime/review PASS artifact; không tự gán evidence đó cho current source-loss candidate.
- `VERIFIED source+unit reclaim review follow-up`: `ERROR` hiện ưu tiên `PRESENT` trong decision/audit; exact regressions khóa cả `RuntimeException` và `LinkageError` khi có evidence present. zAuctionHouse V3 có nhánh reason riêng cho public synchronous full-list/no-hard-bound/cross-storage consistency; V3/V4 vẫn `UNAVAILABLE`. Lookup version ném exception hoặc trả null được evaluator chuyển thành `DENIED_ERROR/CAPABILITY_SETUP`. RED→GREEN focused `16/16`; full `167/167`. Không có adapter scan, `ABSENT`, item grant hoặc issuance mới.
- `VERIFIED independent review`: exact-current-hash `cc/claude-opus-4-8` qua 9router trả `VERDICT: PASS`, không blocker. Reviewer xác nhận không có accidental `ELIGIBLE/ABSENT`, issuance path, blocking-evidence ordering lỗi hoặc lookup exception escape trong scope. Low notes: fatal JVM `Error` ngoài `LinkageError` không thuộc contract bắt hiện tại; version chuỗi `"3"`/`"4"` vẫn fallback `UNAVAILABLE`; không ảnh hưởng fail-closed. Claim recovery đã đối chiếu source/test: CAS `PENDING -> DENIED`, startup chỉ recover `PENDING`, giữ `PREPARED` lock.
- `VERIFIED controlled crash recovery`: Paper `1.21.11-131`, Java `21.0.4`, exact candidate `9f96899b…65c41`. Direct force-kill Java child sau marker physical PDC + `Player.saveData()` tạo fixture `NZ1GMT/c9fe8bd1-...`; post-kill DB integrity `ok`, đúng một `PREPARED`, canonical/snapshot `0/0`, claims vẫn 8 `DENIED`. Startup-only giữ nguyên `PREPARED/0/0`; reconnect exact slot reconcile thành đúng một `PUBLISHED` + canonical + snapshot v1/232 bytes. `/matdo sos` deny `PLAYER_INVENTORY`; DB cuối `PREPARED=0`, claim `DENIED=9`, active claim cho code `0`, trigger harness đã gỡ. Evidence: `docs/runtime/2026-08-24-physical-write-canonical-publish-crash-recovery.md`.
- `VERIFIED independent crash review`: exact `cc/claude-opus-4-8` trả `PASS`, không blocker cho controlled single player-slot recovery. LOW ngoài scope: source bị hủy trước reconcile sau publish-submission failure; generator 6 ký tự chưa retry collision; SQLite trigger chỉ kéo dài timing window. Review: `docs/reviews/2026-08-24-crash-recovery-opus-review.md`.
- `VERIFIED post-runtime build`: focused publication/repository `12/12`; full Java 21 `167/167`, `BUILD SUCCESS`, `git diff --check` PASS. Clean rebuild tạo whole-JAR hash `89d8fa…` do ZIP metadata, nhưng entry-level comparison với runtime JAR có cùng size `12,254,921`, cùng `384/384` entries và `0` entry khác byte; `target` đã được khôi phục về exact runtime bytes `9f96899b…65c41`. Không gán runtime proof cho archive hash `89d8fa…`.
- `VERIFIED controlled Paper performance`: BotChecker chunk-holder run `0d6ef437-44f8-4797-985f-b9a950d62dd2`; final probe SHA `aac5bc1f4a03dc962586d07992ee9da41194cd3d3dc72ddecfb7b1ff1a167707`. Samples `1/10/50/100` đều complete; dispatch lần lượt `1.759/1.557/6.901/10.081 ms`, PDC ready `85.141/197.882/692.620/1437.969 ms`.
- `VERIFIED controlled Paper final inventory`: BotChecker run `4c63613c-98b9-4f9d-8a00-bde072d646b9` PASS. Exact physical slot publication tạo code `SQRO58`, journal `PUBLISHED`, snapshot v1 payload 191 bytes.
- `VERIFIED controlled Paper final SOS-present`: run `74e68e4b-91e6-4cee-8dd1-9d2a47a2cd51` PASS; `/matdo sos SQRO58` persist `DENIED/PLAYER_INVENTORY`, không issuance.
- `VERIFIED controlled WorldGuard 7`: WorldEdit `7.3.18+7298-4383852` SHA-256 `712c87…01241`, WorldGuard `7.0.16+2355-f7fded2` SHA-256 `5db2c5…880d`; ItemGuard log `WorldGuard 7 permission hook enabled`. Dedicated account non-op giữ physical sword nhưng zero journal/canonical; cùng sword sau `op` publish `KAAMWE/PUBLISHED`.
- `VERIFIED controlled external-unavailable denial`: final artifact run `ae3fdc6a-ae8e-43db-9556-2e6beb3378ac` PASS; `/matdo sos KAAMWE` deny `PLAYER_VAULTS ... absence cannot be proven`. Claim DB `DENIED`; canonical row + snapshot v1/192 bytes giữ nguyên; reconnect `clear diamond_sword` trả `No items were found`, không issuance.
- `VERIFIED controlled successor reclaim`: artifact `3a388a…50d71`, probe `5fd2ab…42939`; run `357aa5ce-5e2b-4404-83eb-c9338afe5363` PASS. Claim mới `44c88251-605c-4f70-9566-9383f89de171` persist `DENIED/PLAYER_VAULTS`; toàn DB có 8 reclaim claims và tất cả đều `DENIED`, không `PENDING/PREPARED/COMMITTED`.
- `VERIFIED unit regression`: `ReclaimCapabilityEvaluator` chuyển `RuntimeException`/`LinkageError` khi dựng capability hoặc chạy probe thành `DENIED_ERROR/CAPABILITY_SETUP`; focused 13/13 và full 161/161 GREEN. Mục tiêu là không để claim đã `RESERVED` kẹt `PENDING` vì optional adapter/classloader failure.
- `VERIFIED controlled DB latest`: schema v7, integrity `ok`, publications `354` với `PREPARED=0`, tracked/snapshot `512/512`, active claims `0`, controlled trigger `0`. Exact post-receipt publication `ce5d274f-...` giữ `PUBLISHED`, payload/snapshot SHA `cd74a8d9…ae445`; source entity `e09ec250-...` absent sau restart và zero post-removal observations. DB SHA-256 `017a0214f08ebf4a4dcf2d9bb16ef45af0d75cc8668d8677e6129577a3446fec`.
- `EXCLUDED harness runs`: no-holder `/igbench 1` được probe `5fd2ab…42939` từ chối sạch với `ITEMGUARD_BENCH_REJECTED ... mutation=false`, journal count/timestamp không đổi. Actorless `/igbench 1` khi `worldguard-support=true` timeout `0/1` do policy fail-closed, journal cũng không đổi; không dùng hai run này làm publication/performance evidence.
- `OBSERVED artifact`: shaded JAR chứa `org/sqlite/JDBC.class`, Windows x86_64 native library và `META-INF/services/java.sql.Driver`.
- `VERIFIED controlled Paper`: Paper `1.21.11`, Java 21, isolated workspace `E:\AI.WORK\itemguard-paper-smoke`; current controlled ports TCP `57485`, query `36104`. Port cũ `57484` từng collision với outbound ephemeral connection của `douyin_guard.exe`; process người dùng không bị sửa/kill.
- `VERIFIED controlled Paper snapshot`: material, amount, Sharpness 7, display name, lore và plugin PDC sống qua `serializeAsBytes()/deserializeBytes()`; payload 248 bytes, SHA-256 `ce7013fd1f5d40cc008ce070e647c247877d2f84c2f690d6f547c0a08454e05c`.
- `VERIFIED controlled Paper command linkage`: console `/finditem listfinding 1` trả kết quả sau async handoff, không có exception.
- `VERIFIED controlled Paper player boundary`: BotChecker run `68a3b039-8803-4b28-b0b3-bb345a03f087` PASS 8/8 trên protocol 774. Dedicated offline account thấy `/matdo check` trả empty history, `/matdo sos MISSING` bị deny `ID chua duoc theo doi`, health/food giữ 20/20 và GUI đóng. Report: `E:\AI.WORK\itemguard-paper-smoke\botchecker-reports\68a3b039-8803-4b28-b0b3-bb345a03f087.json`.
- `VERIFIED controlled DB postcondition`: DB runtime vẫn schema v5, `reclaim_claims=0` và không có claim code `MISSING`; unknown ID không tạo journal lock.
- `VERIFIED controlled Paper owned-item tracking`: BotChecker run `5ba00bf7-6a6c-4967-ac90-d70e91a4992c` PASS 7/7. Dedicated account nhận đúng một `DIAMOND_SWORD`, bounded inventory scan tạo canonical code `SKMSCU`, `/matdo check` liệt kê item; DB có tracked row + snapshot v1 (194 bytes, SHA-256 length 32).
- `VERIFIED controlled Paper SOS-present denial`: BotChecker run `e223b11c-5fa7-4327-9aed-926487e8e703` PASS 5/5. Exact canonical sword vẫn trong player inventory nên `/matdo sos SKMSCU` bị deny bởi `PLAYER_INVENTORY`. DB postcondition: đúng 1 claim `DENIED`, `PREPARED=0`, `COMMITTED=0`; tracked row và snapshot vẫn tồn tại.
- `VERIFIED controlled Paper external-capability denial`: BotChecker run `4140e96a-07c0-4f43-8c6d-0ccb78094a6a` PASS 6/6. Sau khi controlled console xóa đúng một disposable sword fixture, `/matdo sos SKMSCU` vẫn deny `PLAYER_VAULTS` vì API/version chưa verified. Journal tổng có 2 `DENIED` theo đúng hai lý do, zero `PREPARED/COMMITTED`; tracked row và snapshot vẫn tồn tại.
- `VERIFIED controlled Paper post-fix writeback`: artifact SHA `b84fa2…3f5d`, BotChecker run `df1e75e3-ecd2-442f-aad0-723d7bd135e7` PASS 7/7. Bounded scan tạo code `8RA10U`; sau nhiều scan DB chỉ có 1 tracked row, 1 snapshot, 1 history cho fixture.
- `VERIFIED controlled Paper post-fix exact presence`: BotChecker run `407db28a-b576-4ca3-95bf-4372e1ad2997` PASS 5/5. Exact inventory probe tìm thấy canonical PDC trên item vật lý và deny `PLAYER_INVENTORY`; DB có 1 `DENIED`, zero `PREPARED/COMMITTED`, tracked row + snapshot còn nguyên.
- `OBSERVED controlled shutdown`: ItemGuard disable và `Database connection closed`; Paper test process mắc ở Moonrise worker-pool termination sau khi save chunks/I/O nên phải kill sau khi port đã nhả. Không coi process exit là verified.
- `VERIFIED controlled player-slot multi-copy`: exact fixture `NZ1GMT/c9fe8bd1-...` ở slots `5/8`; read-only atomic capture bắt đúng two completed observation rows và một `CONFIRMED/NOTIFY` finding cùng epoch, stats `0→1`; physical verify sau 75 giây giữ cùng serialized SHA-256. Canonical/snapshot/publication/claims giữ nguyên, issuance tắt. Evidence: `docs/runtime/2026-08-24-controlled-player-slot-multi-copy-anti-dupe.md`.
- `VERIFIED controlled player + isolated single chest`: exact PLAYER slot 8 + CONTAINER holder `BLOCK:34486cdf-...:-7:-60:10` slot 3 cùng epoch `1787559194263`; read-only capture đúng two rows only, one `CONFIRMED/NOTIFY`, stats `0→1`; same serialized SHA qua 75 giây và disk restart; cooldown giữ finding/stats `1/1`; no publication/reclaim/issuance mutation. Evidence: `docs/runtime/2026-08-24-controlled-player-single-chest-multi-copy-anti-dupe.md`.
- `VERIFIED controlled isolated single-chest source-loss`: exact publication `c07df7d6-...` giữ `PREPARED` khi chest slot 3 đã graceful-persist empty; full-world NBT + prestart seal + restart negative giữ canonical/snapshot `0/0`, reclaim unknown/no issuance. Exact same-block/slot bytes restore chuyển same publication sang `PUBLISHED`, canonical/snapshot +1, exact physical/publication/snapshot SHA và durable `CONTAINER` observation sau `restoredAt`; graceful stop + offline NBT xác nhận một restored item/PDC. Final `ag/claude-opus-4-6-thinking` review PASS. Evidence: `docs/runtime/2026-08-25-controlled-block-container-source-loss-reconciliation.md` và `docs/reviews/2026-08-25-block-container-source-loss-opus-review.md`.
- `VERIFIED controlled player-slot post-receipt source-loss`: exact fixture `DPYVQY/1ecd0eb7-...`, publication `c6ab6cc3-...`, source slot 8 và tagged SHA `e68e6aa5…e1b93`. Rollback journal tồn tại trước removal và sau `Player.saveData()` empty khi exact reconciliation transaction bị delay; same publication commit `PUBLISHED`, canonical/snapshot +1 exact digest. Graceful stop/restart + offline NBT giữ source absent, zero post-removal observations; hai reclaim attempts đều `DENIED ... absence cannot be proven`, active claims 0, no issuance. Hành vi là point-in-time receipt semantics; production change `NO`. Independent fallback `ag/claude-opus-4-6-thinking` review PASS sau Opus 4.8 HTTP 429. Evidence: `docs/runtime/2026-08-25-controlled-post-receipt-source-loss.md` và `docs/reviews/2026-08-25-post-receipt-source-loss-opus-review.md`.
- `VERIFIED controlled player-slot post-receipt unclean-stop recovery`: exact fixture `UAYB03/9d97a539-...`, publication `c96b7113-...`, source slot 8, exact 231-byte SHA `db62fc48…5866df`. Clone-only trigger giữ reconcile transaction; source remove + `Player.saveData()` rồi exact Java child dừng không sạch sau 68 ms. Raw DB/journal/playerdata giữ hash; restart trả same publication `PREPARED`, canonical/snapshot `0/0`, physical source absent, reclaim unknown-ID fail-closed, no issuance. Negative evidence SHA `de650fa5…31bd5` được seal trước trigger removal/restore. Exact same-player/slot bytes restore chuyển same publication sang `PUBLISHED`, canonical/snapshot đúng một, totals `507/507/346`, `PREPARED=0`, trigger/active claim `0`; offline NBT giữ đúng một exact item. Final evidence SHA `728c7c8c…c5dc`; `cc/claude-opus-4-8` review PASS, production change `NO`. Evidence: `docs/runtime/2026-08-25-controlled-post-receipt-crash-recovery.md` và `docs/reviews/2026-08-25-post-receipt-crash-recovery-opus-review.md`.
- `VERIFIED controlled entity source-loss before physical write`: exact tokenized ground entity `cd891b82-...` bị remove trên Paper main thread sau journal marker và trước PDC write; publication `4ec305e0-...` kết thúc `ABORTED/SOURCE_CHANGED`, exact canonical/snapshot `0/0`. Positive entity UUID mới `69d85269-...` publish `0HC2MK/6c86303a-...`, exact 230-byte SHA `9f511605…9ef2e`; exact entity/PDC persist qua graceful restart. Carryover entity được tách riêng khỏi exact oracle; attempts WorldGuard/inconclusive không dùng làm primary proof. Final evidence SHA `696080ec…4910f`; fallback `ag/claude-opus-4-6-thinking` review PASS, production change `NO`. Evidence: `docs/runtime/2026-08-25-controlled-entity-source-loss.md` và `docs/reviews/2026-08-25-entity-source-loss-opus-review.md`.
- `VERIFIED controlled entity post-receipt source-loss`: exact loaded entity `e09ec250-...`, identity `B2L0LU/da116b55-...`, publication `ce5d274f-...`, 235-byte SHA `cd74a8d9…ae445`. No-journal-at-arm + one serial SQLite owner + exact-only 30M `AFTER UPDATE` trigger định vị uncommitted reconciliation window; exact UUID/PDC/SHA revalidated rồi source remove, same publication commit `PUBLISHED`, canonical/snapshot exact SHA, zero post-removal observations và source absent sau restart. Independent SQLite falsifier bác bỏ giả định `updated_at=commit time`; primary `cc/claude-opus-4-8` correction review PASS, findings none, production change `NO`. Final evidence SHA `f79f6de6…9c86`; archive manifest `f52e2eaa…9c6e`. Evidence: `docs/runtime/2026-08-25-controlled-entity-post-receipt-source-loss.md` và `docs/reviews/2026-08-25-entity-post-receipt-source-loss-opus-review.md`.
- `VERIFIED controlled container post-receipt source-loss`: exact isolated chest slot 3 `58AC7W/d5df7e1c-...`, publication `e4b5aa86-...`, 235-byte SHA `7ae92308…07151`. Fresh ready cache + canonical0, no-journal-at-arm, one serial SQLite owner và exact-only delayed trigger; watcher revalidate exact block/slot/PDC/SHA rồi remove + world-save trong active journal window. Same publication commit `PUBLISHED`, canonical/snapshot exact SHA, zero post-removal observations; full-world NBT parse `1,347` chunks và restart giữ exact slot/code/UUID absent. Final evidence SHA `a16e66e7…74c23`; exact `cc/claude-opus-4-8` review PASS, production change `NO`. Evidence: `docs/runtime/2026-08-26-controlled-container-post-receipt-source-loss.md` và `docs/reviews/2026-08-26-container-post-receipt-source-loss-opus-review.md`.
- `VERIFIED controlled entity chunk-unload/reload`: exact remote entity UUID `b2b0f8aa-...`, identity `U6I2ZW/6fe5fd27-...`, publication `32661d99-...`, 236-byte SHA `6d09d0d2…fecd3`. Exact delayed reconciliation trigger + stable journal; no-holder/PDC/SHA revalidation; `unloadChunkRequest` accepted, exact `EntitiesUnloadEvent`, chunk absent và UUID unresolvable while transaction active. Same publication PUBLISHED; offline NBT parse 30 entity chunks found exact UUID/code/item UUID once; restart exact `EntitiesLoadEvent`, same UUID/PDC/SHA and readiness PASS. Opus 4.8 correction review PASS, production change `NO`. Evidence: `docs/runtime/2026-08-26-controlled-entity-chunk-unload-reload.md` và `docs/reviews/2026-08-26-entity-chunk-unload-opus-review.md`.
- `VERIFIED controlled repeated concurrent publication stress`: run `8c636fd4-...`, 5 wave × 100 non-stackable ground entities; mỗi wave exact candidate `inFlightSources=100`, total 500/500 commit-before-ready trong 17.969 giây. DB exact delta tracked/snapshot/publication `+500/+500/+500`, exact 500 attributed PUBLISHED rows, zero delta PREPARED/ABORTED/history/observation/finding/claim/trigger. Cleanup/restart và all-dimension entity NBT sweep (4 files/31 chunks) giữ exact 500 UUID/code/item UUID absent. Focused `25/25`, full `201/201`; Opus 4.8 PASS, findings none, production change NO. Evidence: `docs/runtime/2026-08-26-controlled-repeated-concurrent-publication-stress.md` và `docs/reviews/2026-08-26-publication-stress-opus-review.md`.
- `VERIFIED controlled entity destruction while unloaded`: exact published entity `GZNELR/e24627fd-...`, UUID `816684e4-...`, 239-byte SHA `7ea6b91f…1c33f`, remote chunk `(19,0)`. Real unload event/chunk absence PASS; server-offline exact region mutation removed one target only, target count `3→2`, all-world identity hits `1→0`, non-target fingerprints/regions preserved. Restart load event không chứa UUID, runtime count đúng 2, exact PDC absent; logical DB publication/canonical/snapshot và all counts giữ phase-1 baseline, integrity ok, issuance false. Focused `29/29`, full `201/201`; pre-runtime Opus 4.8 correction PASS, final fallback Opus 4.6 PASS, production change NO. Evidence: `docs/runtime/2026-08-26-controlled-entity-destruction-while-unloaded.md` và `docs/reviews/2026-08-26-entity-destruction-while-unloaded-review.md`.
- `NOT VERIFIED / UNSUPPORTED TOPOLOGY`: multi-writer, multi-server/shared/network filesystem, mixed old/new jars và external SQLite writers. ItemGuard hiện hỗ trợ exactly one cooperating owner trên same-host local filesystem; không quảng bá multi-writer. Hopper minecart/entity/virtual inventory chỉ fail-closed, không có persistence/transfer support. Same-tick hai block hoppers, isolated single-chest pre-write chunk unload/retry, same-location exact-byte block replacement/retry và logical double-chest chunk-border both-unload/retry đã verified. Natural break đã verified factual client-load/Paper/Bukkit path tới content entity-add, nhưng fresh identity/publication tail vẫn inconclusive; place/physics, hơn hai initiators/hopper density, unopened scanning, throwing custom inventory accessor, performance, offline/disconnect timing, ItemGuard destruction observation/tombstone, relocation recovery, concurrent crash/unload stress, non-chest container cases, external absence lookup thật PlayerVaultsX/zAuctionHouse, destructive quarantine và production vẫn chưa verified.
- `MITIGATED controlled`: first-tag mutation path không còn chờ SQLite transaction trên Paper main thread. Static audit chỉ còn `future.get()` trong synchronous DB compatibility boundary; pipeline entity/inventory mới dùng async owner/coordinator.

Compile regression `PlayerBrowserGUI.createGui(...) has private access` đã được sửa tối thiểu bằng public entry points hiện hữu. Hai lỗi test cũ (material display name và location parser) đã có regression coverage và đang xanh.

## Release gates

- Automatic duplicate deletion/punishment: `DISABLED / NOT VERIFIED`.
- Reclaim `/matdo sos`: `IMPLEMENTED FAIL-CLOSED / CONTROLLED RUNTIME VERIFIED FOR DENIAL`; chỉ prepare, capability check và journal denial, không cấp item.
- PlayerVaultsX/zAuctionHouse lookup: `AUDITED VERSION-AWARE UNAVAILABLE`; support matrix phân biệt official `PlayerVaults` 4.4.x, Modrinth `PlayerVaultsX` 1.0.x và zAuctionHouse V3/V4. Public API có tồn tại ở official PlayerVaults/zAuctionHouse nhưng sync I/O/full-bucket, side effect hoặc thiếu bound/read-consistency; không trả `ABSENT`. External runtime lookup thật chưa verified.
- `/finditem` workflow: `IMPLEMENTED / UNIT+INTEGRATION VERIFIED`; `TAKE` vẫn chỉ là persisted intent, không tịch thu.
- Drop/chest/trade restrictions: `NOT IMPLEMENTED / NOT VERIFIED`.
- Placeholder `%id%`: `NOT IMPLEMENTED / NOT VERIFIED`.
- Controlled Paper runtime: `PARTIAL VERIFIED`; hopper topology block-container positive + entity/virtual fail-closed ingress/egress, same-tick two-block-hopper competition, isolated block-container pre-write chunk unload/retry, same-location exact-byte block replacement/retry, logical double-chest chunk-border both-unload/retry và same-host cooperative SQLite single-owner startup/clean/force-kill handoff PASS. Natural break factual client-load/JDI/Bukkit break/drop/entity-add path PASS, nhưng fresh identity/publication tail `INCONCLUSIVE` và product verdict `NOT_ISSUED`. Multi-writer/multi-server/shared filesystem không phải supported topology; destruction/tombstone, broader crash/unload stress, relocation, natural break completion/place/physics, hơn hai hoppers/density, unopened/custom inventory/performance, external và destructive action vẫn chưa đủ.
- Production: `NOT DEPLOYED / NOT VERIFIED`.

## Blocker đã quan sát

1. `MITIGATED unit/integration`: event/history không còn kích hoạt duplicate; destructive detector/full-world scan cũ đã bị loại. Duplicate domain chỉ `CONFIRMED` từ hai exact observations trong completed epoch.
2. `MITIGATED unit`: identity resolver phân biệt `ABSENT/COMPLETE/CORRUPT`; đã xóa heuristic restore theo material/name.
3. `MITIGATED unit`: inventory mutation dùng exact slot với budget; pickup tag source entity, không ghi nhầm main hand.
4. `MITIGATED source/integration+controlled`: SQLite dùng một connection do một serial executor sở hữu và sidecar OS lock giữ exactly-one cooperating owner. Owner thứ hai fail startup trước JDBC; init/close/release uncertain paths giữ lock tới process exit; clean/force-kill handoff đã controlled verified trên local fixed NTFS. Không suy rộng sang multi-server/shared filesystem.
5. `MITIGATED integration+controlled`: schema v6 có future-version rejection, durable tag-publication journal, unique UUID/source/code invariants, observation ledger, search requests, versioned snapshot và reclaim journal.
6. `MITIGATED controlled Paper`: snapshot có version, size bound, SHA-256, corrupt/future rejection, restart persistence và semantic Paper round-trip cho material/amount/enchant/name/lore/PDC.
7. `MITIGATED fail-closed`: `/matdo sos` reserve claim trước capability scan; `PENDING` crash recovery thành `DENIED`, `PREPARED` giữ lock; PV/AH unavailable và issuance gate đóng nên không có item nào được cấp.
8. `MITIGATED source/integration`: identity+snapshot durable transaction hiện propagate failure; `tagItem()` trả item gốc chưa tag nếu commit lỗi. SQLite bật `PRAGMA foreign_keys=ON`.
9. `MITIGATED source/unit`: `/finditem` parse/permission trên main thread, DB task async, render quay lại main thread.
10. `MITIGATED controlled`: WorldGuard integration mặc định tắt; API thật `WorldGuardPlugin.inst().wrapPlayer(player).hasPermission("itemguard.track")`, softdepend và runtime 7.0.16 allow/deny đã verified. Hook/query/permission lỗi vẫn deny.
11. `MITIGATED controlled`: async first-tag main-thread performance, entity post-insertion locator và exact inventory physical handle đã có regression + controlled evidence.
12. `MITIGATED fail-closed / capability open`: external plugin detection/version reason đã có; PlayerVaults/zAuctionHouse absence proof vẫn `UNAVAILABLE` vì public APIs hiện sync file I/O/full-bucket, side effect hoặc thiếu hard bound/thread/read-consistency contract. Modrinth PlayerVaultsX fork thiếu enumerate/read API. Issuance, transfer restriction và PlaceholderAPI chưa hoàn thiện.
13. `MITIGATED unit+controlled`: capability setup/probe `RuntimeException` hoặc `LinkageError` sau reserve được biến thành denial evidence và persist `DENIED`; successor controlled journey không để claim pending.

Chi tiết và mitigation nằm trong `docs/RISK_REGISTER.md`.

## Việc đang thực hiện

1. Hopper/block-container/SQLite gates trước đã controlled PASS. Natural break đã tới factual content entity-add qua Attempt 12; Attempt 14 có execution history `NOT_ESTABLISHED` và bundle integrity loss, nên được consume fail-closed. Không rerun Attempt 12/14, không reuse receipt/bundle 18/34 và không restore/whitelist quarantine. Trước namespace mới phải chốt explicit antivirus-safe successor design, TDD/review receipts cho ItemListener invocation, deferred stable entity, `requestEntityTag` outcome, publication state/detail, bounded entity state, stopped DB/NBT và restore-safe deploy; successor cần sealed contemporaneous execution journal để phân biệt chưa launch, spawn attempt và exit. Không sửa product khi chưa có RED product defect. Tombstone/broader crash stress, hơn hai hoppers/density, relocation/non-chest/unopened/custom inventory/performance còn mở.
2. Chờ/đề xuất upstream PlayerVaults + zAuctionHouse bounded, side-effect-free identity lookup với thread/read-consistency contract; unavailable tiếp tục deny.
3. Chỉ thiết kế issuance transaction sau khi mọi capability có thể chứng minh absence; release gate hiện đóng.

## Boundary

Không commit/push/deploy/restart production trong trạng thái này nếu chưa có yêu cầu/phê duyệt rõ ràng. Build/unit test về sau không được báo là runtime verified.
