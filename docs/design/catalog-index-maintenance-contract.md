# Catalog index maintenance contract — proposed, not shipped

## Evidence and review disposition

Current schema8 uses tracked_items(code VARCHAR(16) PRIMARY KEY, item_uuid UNIQUE); code is NOT an INTEGER PRIMARY KEY alias. SqliteConnectionOwner opens one connection on SerialDatabaseExecutor, disables autocommit before schema initialization, commits each operation or rolls back exceptions. Source read: SqliteConnectionOwner:66-89,202-218; SqliteSchemaManager:26-43; ItemSqliteRepository:1366-1387.

CatalogFunctionLifecycleProbe executed with Java21 and target/ItemGuard-1.0.0.jar: arity=1 native_destroy_rc=0 remains_active=true; arity=-1 native_destroy_rc=0 remains_active=false. javap of packaged org.sqlite.Function confirms destroy(Connection,String,int) ignores the int and discards DB.destroy_function return value. This establishes the observed arity-dependent behavior, not the native C implementation cause. Earlier 'missing UDF allowed write' is NOT evidence of a write without an available function: the registered function remained callable. Fresh unregistered connection rejection remains separate evidence.

B1 accepted as lifecycle design gap, corrected interpretation above. B2 accepted as audit gap, not proof that an unknown caller automatically bypasses a SQLite trigger: triggers attach to the table, not Java callers. External schema tampering is outside normal application-writer guarantee. B3/B4/B5/B6 remain design/integration gaps, no implementation PASS.

## Connection normalization contract

Reserve a new versioned name ig_catalog_index_fold_v1, one argument, deterministic, NULL→empty, NFC then Locale.ROOT. Register before schema/index validation or any writer admission, for the entire connection lifetime. No per-query destroy or redefinition. Keep existing transient ig_catalog_fold separate. Registration/known-vector failure denies index startup; no claim that missing function means empty result. Close connection for cleanup. Normalization version change invalidates readiness until rebuild; Java runtime changes require compatibility evidence, not blindly retaining a prior stamp.

## Writer inventory — observed, not exhaustive

- ItemSqliteRepository:1366 upsertItem: INSERT and ON CONFLICT(code) UPDATE; changes name/material, owner and metadata. Same UUID required. Does not change existing code/UUID in update branch.
- ItemSqliteRepository:161 and187: owner/location/last-seen updates. Owner remains a canonical-table filter; no duplicated owner in FTS.
- SqliteSchemaManager:26-43: creates table/identity index. No search maintenance currently.
- Whole src/main/java search for tracked_items found these SQL write sites; all upsert callers and unrestricted SqliteOperation entrypoints still require audit before claiming exhaustive coverage. No REPLACE/delete site found by that search; this is not an external-writer guarantee.

## Identity decision proposed

Do NOT bind FTS identity directly to tracked_items hidden rowid: code is a text primary key and hidden rowid stability across maintenance is not guaranteed. Use derived catalog_search_documents(id INTEGER PRIMARY KEY, code TEXT NOT NULL UNIQUE, item_uuid TEXT NOT NULL UNIQUE, folded columns). FTS rowid refers only to this explicit id. Join results back by code AND UUID, order/cursor by canonical code with existing collation, never integer id. Upsert of same canonical identity retains derived id; insert allocates; delete removes both derived document and FTS within one transaction. Identity mutation and REPLACE must be either explicitly rejected or fully covered by tested trigger logic; not assumed equivalent to UPSERT. Do not ship until this choice and schema are reviewed.

## Readiness/routing contract proposed

Correction from product input audit: CatalogQuery:11-15 already rejects all ISO control characters, including U+0000, in text/owner/cursor. Preserve this rejection; do not broaden the public API to accept NUL via fallback. The synthetic Unicode probe exercised a wider domain than the product API. CatalogRepositoryTest now explicitly covers NUL rejection in all three fields; this is existing-behavior coverage, not a RED→GREEN product fix. Source item values containing NUL remain a distinct compatibility concern.

States: UNAVAILABLE (no installed index), BUILDING, VALID, INVALID. VALID is connection-local evidence from supported schema/normalization/trigger definitions plus completed source-index reconciliation, not merely a persisted boolean. Reopen begins UNAVAILABLE until validation; interrupted backfill cannot become VALID. Known drift, corruption or normalization change makes INVALID before another indexed read.

Input validation runs first: ISO-control input including NUL is rejected by CatalogQuery, never routed to fallback. For accepted input, MATCH is permitted only in VALID, with >=3 Unicode codepoints after normalization and verified source text-domain compatibility. Other accepted input uses the canonical exact bounded path. FTS errors invalidate indexed readiness and surface an error; no silent empty result, partial results or budget extension. Empty queries retain canonical browsing path. Readiness check and query run on the same serialized connection operation. No safe concurrent schema tampering guarantee; unsupported direct DB mutation requires shutdown/revalidation.

## Migration remains blocked

Bounded transaction/backfill, reconciliation budgets, long-value costs, index-definition checks, interrupted/disk-full rollback, restore/reopen and actual repository integration are not implemented. No server mutation in this slice. Next acceptance: review this contract and complete caller inventory, then bounded offline tests against real repository before migration integration. Server permission at E:\MinecraftServer\VillageDefense - Comeback does not replace these gates.
