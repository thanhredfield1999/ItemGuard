# Retained observations review disposition

R1 `obs-review.json` is INVALID (no verdict, only promised inspection). Exit0 does not count.
R2 `obs-r2-review.json` Opus5 PASS_FOR_OFFLINE_CANDIDATE with six findings. No runtime approval. R2 input hash preserved in `obs-r2.manifest.json`; source changed after R2 so final followup required.

1. MEDIUM mixed epochs: CONFIRMED UX ambiguity. Added cross-epoch ≠ simultaneous/dupe warning and scan epoch on each list row. Behavioral RED `obs-cross-epoch-red.log`; GREEN `obs-review-fixes-green.log`. Preserves all recorded positions, never chooses sole holder.
2. MEDIUM shared 1s gate: OBSERVED, intentionally retained safety contract, not a new correctness failure. Flow test uses real JDBC+gate+controller and deterministic clock to prove retry after1s. Fairness/normal-navigation refusal and generic admission-vs-DB error text remain UX limitations (C1/C4); no automatic requeue/unbounded work or weakening cap introduced. Reviewer explicitly nonblocking.
3. LOW slot null/negative: PARTLY CONFIRMED. Negative actually displayed -1 (not 0 as reviewer said); list/detail now render unknown, RED→GREEN. NULL is rejected by current NOT NULL schema; added real JDBC rollback/retained valid row test. No schema/migration change or asserted handling of structurally invalid DB schema.
4. LOW profile source: CONFIRMED. `tracked_items` location/time now explicitly labeled aggregate record, not newest observation. RED `obs-profile-label-red.log` → GREEN.
5. LOW unused UDF: ACCEPTED residual baseline cost; bounded() shared cleanup preserved unchanged, no speculative refactor. Query/interrupt recovery tests pass with populated result following failure.
6. LOW historyFailure name: correct shared detail error context, RETAINED to avoid unrelated rename. Existing tests ensure observation failure hides catalog filters and returns exact selected profile.

Further coverage: CatalogObservationFlowTest runs actual SQLite -> gate -> controller, caches are not claimed live, pruning requery becomes explicitly unknown not absent. Initial harness lacked tracked_items.last_seen_at and errored; corrected test seed only, this is coverage NOT production RED.

Review boundary context for followup includes exact schema, owner, repository write/prune ranges and session. Whole C3/custody/HavenBags/current-holder/visual/Paper/release remain OPEN. Server target path unanswered; no fixture reuse or external system writes.
