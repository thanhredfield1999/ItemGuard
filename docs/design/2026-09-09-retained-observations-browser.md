# Retained observations browser — bounded C3 slice

User approved observation-first with visible gaps in clarify; server/artifact path remains unanswered. No new schema, writer, scan, adapter, custody inference or ownership authority. Existing catalog profile navigation extended, not redesigned.

## Exact-current semantics
- ItemSqliteRepository.recordObservation upserts same epoch/physical key/item identity, queues on owner. completeObservationEpoch deletes ALL earlier epochs; this table is not durable movement history.
- InventoryScanTask scans online player inventories + open physical block containers. epoch_complete only describes completion of that bounded scan, never global completeness/offline inventories/external storage.
- SELECT exact code AND item_uuid, descending scan_epoch then observation_id (compatible identity/epoch index), LIMIT101 to return100 + truncation flag. Shared owner/ProgressHandler/admission retained; failures remain errors, not empty. No schema change or increased budgets. Holder ID preview capped255 with explicit shortened marker; raw type capped64, unknown types displayed as unknown.
- Immutable scalar DTO only. No item deserialization, player UUID/name lookup, world access, container contents view, or write.

## UX/security
- Profile slot24 becomes Nơi đã quan sát; loading, list36/page (max100 retained rows), detail, back profile/catalog preserving filters. Each row shows recorded physical type/id/slot, time, scan epoch and completion flag.
- Require existing history + history.others in addition to gui/search before submit, after completion and every action, matching retained-history privacy.
- Explicit no-current-holder/no-absence/no-transfer claims, observation cache pruning and HavenBags unsupported. Empty != absent; completed epoch != globally complete; time invalid/nonpositive displayed unknown.
- Async failure/retry exact selected identity, no catalog filters on observation errors. Shared gate one inflight/1s. Revocation, foreign UI/close/quit/disable/stale callback covered offline, not Paper evidence.

## Gates
TDD JDBC query actual rows/isolation/order/cap/partial/retention/budget recovery; controller navigation/error/retry/paging/permissions; actual CatalogUi host boundary. Full Java21 package and exact reviewer/artifact match. GUI visual/Paper, HavenBags version-specific integration, durable custody and whole C3 remain OPEN.
