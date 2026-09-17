# Controlled repeated concurrent publication stress

Ngày: 2026-08-26

## Kết luận

`VERIFIED controlled Paper` cho repeated concurrent first-publication/backpressure scope hẹp: 5 wave tuần tự, mỗi wave 100 ground-item entity non-stackable được tạo gần đồng thời trong một naturally loaded chunk.

Tất cả 500 entity đạt durable readiness; mỗi wave quan sát `maxInFlight=100`; DB tăng đúng 500 publication/canonical/snapshot, không tăng PREPARED/ABORTED/history/observation/finding/claim/trigger. Cleanup, graceful restart và full all-dimension entity NBT sweep xác nhận toàn bộ 500 entity/PDC absent.

Production source/JAR/server không đổi. Project vẫn `NOT RELEASE READY`.

## Exact run

- Candidate: `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`.
- Run token: `8c636fd4-ba8d-4727-b431-149518e0acb8`.
- Probe SHA: `f61fc44b5a24484c6353c95981ce598f0ec233aa0c72833cb10c77040320fedd`.
- Workload: `5 × 100 = 500` diamond swords, one loaded chunk, at most 100 live controlled entities per wave.
- Elapsed: `17,969 ms`.
- Wave durations: `3,003 / 3,778 / 2,920 / 3,175 / 4,726 ms`.
- `maxInFlight`: `100 / 100 / 100 / 100 / 100`.

## Oracle

1. Controller binds reflectively to the running candidate classloader. Missing fields, wrong classloader hoặc wrong type hard-fail.
2. `inFlightSources` is sampled each tick; every wave requires `>=50`, observed `100`.
3. Every item must return `isEntityIdentityReady=true`. Source trace proves both readiness routes latch only after `SqliteConnectionOwner.runTransaction` commits and the completion returns to main thread.
4. Controller then captures exact entity UUID, source key, code, item UUID, serialized bytes/SHA; uniqueness is strict across all 500.
5. Manifest is atomically written before exact wave entities are removed.
6. Offline DB seal independently queries every source key/code and run-token-prefixed row; exact payload bytes/SHA must match live manifest.
7. Restart verifies all 500 UUIDs remain unresolved.
8. Offline NBT parser sweeps `entities/*.mca` in overworld/nether/end: 4 files, 31 entity chunks, zero exact entity UUID/code/item UUID hit.

## DB evidence

Baseline:

- tracked/snapshots/publications: `515/515/357`;
- PREPARED/ABORTED: `0/9`;
- history/observations/findings/claims: `7/0/1/12`;
- active claims/triggers: `0/0`;
- integrity: `ok`.

Final:

- tracked/snapshots/publications: `1015/1015/857` — exact delta `+500/+500/+500`;
- PREPARED/ABORTED: `0/9` — zero delta;
- history/observations/findings/claims: `7/0/1/12` — zero delta;
- active claims/triggers: `0/0`;
- exact attributed rows: `500`, all `PUBLISHED`, detail null;
- exact canonical/snapshot: `500/500`;
- integrity: `ok`.

History zero-delta is expected because ground-item publication has owner null; this run does not cover owner-attributed history.

## Harness corrections

Hai assertion sealer được sửa sau runtime mà không thay đổi runtime artifact:

- cleanup expected `removed=0, alreadyAbsent=500`, vì wave teardown đã remove exact entities post-commit; cleanup command là audit sau đó;
- expected-count map phải chỉ so sánh count fields, không trộn `schema/integrity` từ baseline.

Sau hai correction, exact DB/cardinality/SHA seals PASS. Không sửa fixture result hoặc DB để làm xanh.

## Verification

- Contract: RED khi thiếu controller, sau implementation `ITEMGUARD_PUBLICATION_STRESS_CONTRACT_GREEN`.
- Focused Java 21: `25/25` PASS.
- Full Java 21: 58 suites, `201/201` PASS, zero failures/errors/skips, `BUILD SUCCESS`.
- Clean build `a1589247…68929` và runtime candidate có `400/400` non-manifest entries byte-identical; target được khôi phục exact candidate.
- `git diff --check` PASS.
- Final Opus 4.8: `VERDICT: PASS`, findings none, production change `NO`.

## Scope limits

Không chứng minh multi-player/multi-writer, simultaneous commit contention, chunk unload, crash/force-kill, stackable/merge/split, production TPS/scale hoặc OS fsync durability ngoài SQLite `connection.commit()`.

## Evidence

- `E:\AI.WORK\itemguard-paper-smoke\publication-stress-final-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\publication-stress-phase-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\publication-stress-nbt-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\publication-stress-run-orchestrator.log`
- `E:\AI.WORK\itemguard-paper-smoke\publication-stress-restart-orchestrator.log`
- Review: `docs/reviews/2026-08-26-publication-stress-opus-review.md`.
- Archive: `E:\AI.WORK\backups\itemguard-publication-stress-8c636fd4-20260826-082502.tar.gz`, SHA-256 `b21d153bdd95561afcada769c382492a7064256744daa0a571b24a9d9e660d58`; manifest SHA-256 `1be09ab9d9935d8be3d3e47a3127248d65cc725b0dddb4f4c31bf7aa6c41206b`.
