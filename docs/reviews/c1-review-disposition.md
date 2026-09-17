# C1 implementation review disposition

Review `c1-implementation-review.json` returned conditional PASS_FOR_OFFLINE_CANDIDATE. Parent does not treat conditions as already satisfied or extrapolated sizing as measured facts.

- F1 CONFIRMED: generic SQLITE_INTERRUPT lacked migration context. Behavioral `c1-diagnosis-red.log` -> `c1-diagnosis-green.log`; adds history-index budget/check/elapsed/startup-denied diagnostic with original SQLite exception as cause. No timeout/cap increase.
- F2 REJECTED as a supported admission/free-space claim: 8.3M rows / 2x free disk extrapolated from a single shape and 1M workload is not verified across row widths, cache, journal/temp space and disk pressure. Document measured 12k callbacks/100k cap and byte delta only. Larger-size support/required free space needs separate measurements and maintenance approval; never publish 8.3M/2x as safe limits.
- F3 CONFIRMED pre-existing adjacent cleanup weakness: CatalogRepository cleanup could mask original query failure. Added small package-private cleanup seam, two behavioral RED tests -> GREEN, integrated into actual query finally. Both cleanup actions attempted; original retained with suppressed cleanup errors; success becomes error if cleanup fails. Setup handler now inside try so fold UDF is removed on handler-setup failure. Dependency-level cleanup failure injection not claimed; seam tests plus real JDBC query/error/recovery probe.
- F4 NO NEW RETRY: schema handler-clear error fails initialization, existing SqliteConnectionOwner closes failed-init connection before ownership release. Rollback can fail and is suppressed; no reuse is promised. Reattempting handler cleanup or live retry to mask errors is not needed for fail-closed slice. No cleanup-native-failure or disk/full power-loss claim.
- F5 DEFERRED hypothetical future proxy owner: production owner creates direct DriverManager SQLite connection; no proxy there today. Do not expand adapter contract for imagined future owner.
- F6 NOT CONFIRMED for normal close path: SqliteConnectionOwner closes through queued closeConnection then SerialDatabaseExecutor.shutdown/await; queued pre-close tasks drain first. Close timeout retains lock and does not forcibly discard queue in that branch. Other shutdown/hung-I/O semantics pre-exist and remain fail-closed. Adding timeout-release to gate could admit overlapping reads while old SQL still runs, violating one-inflight. No change.

Required coverage:
- Added index/query-plan assertions to existing fresh/v1/v6 SqliteSchemaManagerTest; existing v7 fixture remains independent frozen history DDL.
- Added RareSuffix: one matching item at final code in 100k tracked records. c1-scale-final.log shows bounded SQLITE_INTERRUPT, not false empty; still general-search-scale limitation.
- Final full Maven c1-full-maven-final.log: 351 tests, failures/errors/skipped 0. C1 scale recompiled against that JAR, all three databases PASS. Previous full tests were 349, not review's inferred test count.
- DatabaseManager has no schema_version/CURRENT_SCHEMA_VERSION writes; it delegates SqliteConnectionOwner. CatalogCategory predicates are closed enum SQL with no user interpolation or placeholders. Include both plus original legacy tests and final build excerpt in followup.

Followup asks acceptance of these exact dispositions and the F1/F3 patch, not broad new runtime approval. No Paper/production/release authorization.
