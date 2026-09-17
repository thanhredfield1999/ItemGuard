# Catalog writer call paths — static bounded audit

Scope src/main/java, no external plugin/reflection/arbitrary SQL guarantee. Source search + targeted reads, not runtime coverage.

| Entry | Canonical write | Transaction |
|---|---|---|
| DatabaseManager.saveItem → ItemSqliteRepository.saveItem:57 | upsertItem:1366 | owner.execute |
| DatabaseManager.saveItemWithSnapshot → repository:64 | upsertItem + snapshot | owner.call |
| repository.publishTagPublication:87 | private publishTagPublication:1237 → upsertItem:1250 + snapshot + publication transition | owner.call |
| repository.publish:117 | same private publication path | owner.callAsync |
| reconcileTagPublication:1273, call at1307 | same private publication path when reconciliation permits | inspect full reconciliation contract before integration tests |
| updateLastAction:151 | owner/action/location/time only | owner.execute |
| updateLocation:178 | owner/location/time only | owner.execute |

SqliteConnectionOwner.runTransaction:202 commits operation result, rolls back thrown failures. Public SqliteOperation permits arbitrary Connection operations; not a security boundary. Search for DriverManager/getConnection in src/main/java found connection creation only in SqliteConnectionOwner:69. DatabaseManager constructs owner and repository; CatalogRepository shares owner and reads canonical data.

The private upsert is reached at59,70,1250. Its ON CONFLICT(code) branch modifies material/name/owner/metadata, NOT code or UUID; it checks equal UUID. No tracked_items DELETE/REPLACE found in literal table search. SchemaManager creates canonical table and indices. This strengthens shipped-source inventory but does not prove absence of obfuscated/dynamic SQL or hostile external modification.

Trigger architecture means unseen normal DML callers still execute table triggers; caller enumeration is for behavior/tests, not the mechanism providing atomicity. Schema alteration, REPLACE conflict semantics and direct derived-table tampering require explicit unsupported-operation/validation policy. External missing UDF should reject dependent DML, not bypass maintenance. Current source does NOT install these triggers.

Integration test targets: save; save+snapshot failure rollback; sync/async publication commit and transition failure rollback; reconciliation publish; owner-only updates preserving FTS identity; duplicate canonical identity rejection. Compare canonical code+UUID+folded fields after each transaction, not row counts alone. C1 remains open.
