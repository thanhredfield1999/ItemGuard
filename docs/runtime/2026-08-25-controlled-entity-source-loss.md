# Controlled entity source-loss before physical write

Ngày: 2026-08-25

## Kết luận

`VERIFIED controlled Paper` cho scope hẹp một ground-item entity đã load:

1. Probe clone-only spawn một `DIAMOND_SWORD` entity có display name tokenized `ItemGuard Entity Loss Negative c3591a04`.
2. Exact SQLite trigger chỉ match display name tokenized và `source_key LIKE 'ENTITY:%'`, tạo cửa sổ reserve transaction deterministic.
3. Probe quan sát rollback journal ổn định, sau đó remove exact entity UUID trên Paper main thread trước physical PDC write.
4. Exact publication kết thúc `ABORTED / SOURCE_CHANGED`.
5. Exact fixture không tạo canonical row hoặc snapshot row.
6. Positive control dùng một entity UUID mới, tạo đúng một publication `PUBLISHED`, canonical row và snapshot row có exact serialized SHA/length.
7. Sau graceful stop/restart, exact positive entity UUID và PDC identity vẫn hiện diện.

Không phát hiện production defect trong scope này. Production source không đổi.

Project tổng thể vẫn `NOT RELEASE READY`.

## Artifact identity

- ItemGuard runtime candidate SHA-256:
  `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`
- Clone-only probe SHA-256:
  `78a69829a0548ae551b289f47009f1292ceef444a145a8336e93cf751a894f64`
- Final evidence SHA-256:
  `696080ec1a63dbf4cb4f7c3e9e49dcbb38fddd4ab54e5fa498843c858b54910f`
- Negative evidence SHA-256:
  `de6f9e462ae5dbeca40f0b025430ced9d96e1508fce9a2ad4103b58415512be0`
- Positive evidence SHA-256:
  `118bba7132d0c59d26eb4df7bbb1a187ca61e4081f96bd30964e511bc8cdb1c7`
- Evidence archive manifest SHA-256:
  `bc6b26154504ea9167b79f68cf101b3d86418518c66fc29d4abe90fed6bfd893`
- Run token:
  `c3591a04-b732-44bd-aa2b-a66869b8df12`

Archive:

`E:\AI.WORK\backups\itemguard-entity-source-loss-c3591a04-b732-44bd-aa2b-a66869b8df12`

## Oracle contract

### Negative

Exact fixture:

- entity UUID: `cd891b82-216b-436b-bd2e-ab38740dd17f`;
- source key: `ENTITY:cd891b82-216b-436b-bd2e-ab38740dd17f`;
- attempt name: `ItemGuard Entity Loss Negative c3591a04`;
- source digest: `ecd76cf57cecac55fea2c819aff2037c8954fb9bcd8a69c851ecc1c7cc10c34c`;
- publication ID: `4ec305e0-fd47-4e97-b070-36c1ec366d4a`;
- reserved code/item UUID: `TWTDOH / 81fe6c42-c0c5-4200-81dc-5fa6c5c92a92`.

Ordering từ marker:

- `spawnedAt=1787648789591`;
- `journalObservedAt=1787648789856`;
- `removedAt=1787648789900`;
- physical stack chưa có `itemguard:code` hoặc `itemguard:item_uuid` trước remove;
- sau remove, `isValid=false` và `Bukkit.getEntity(exactUuid)=null`;
- journal vẫn tồn tại tại removal boundary.

Durable database verdict:

- exact publication `ABORTED`;
- detail `SOURCE_CHANGED`;
- exact canonical count `0`;
- exact snapshot count `0`;
- `PREPARED=0`;
- active reclaim claim `0`.

Journal presence chỉ định vị deterministic reserve window. Verdict chính dựa exact marker ordering, exact source-key audit row và zero exact canonical/snapshot.

### Positive control

Positive control không phải recovery của entity đã remove. Nó dùng locator mới:

- entity UUID: `69d85269-5285-4d7b-97f2-90de04566328`;
- source key: `ENTITY:69d85269-5285-4d7b-97f2-90de04566328`;
- code/item UUID: `0HC2MK / 6c86303a-b33b-474e-81ca-5c47109df3ef`;
- publication ID: `9083de97-5335-447a-a078-2ab5706ef04d`;
- exact serialized bytes: `230`;
- exact serialized SHA-256:
  `9f511605f6fb6366ea291968bfbf99993654556c3389f917eb1998ae6409ef2e`.

Database:

- publication `PUBLISHED`;
- one matching canonical row;
- one matching snapshot row;
- snapshot SHA/length match physical marker;
- after restart, `/igentitylossverify` resolved exact entity UUID and exact PDC identity.

Final controlled DB:

- schema `7`;
- integrity `ok`;
- tracked/snapshot/publication `509/509/351`;
- `PREPARED=0`;
- active claims `0`;
- triggers `0`.

## Attempts không dùng làm primary proof

### WorldGuard actorless boundary

Attempt đầu spawn entity trong khi `worldguard-support=true`. Entity spawn lifecycle không có actor, nên WorldGuard mutation access fail-closed trước reserve. DB baseline vẫn giữ nguyên tại checkpoint đó.

Controlled entity journey sau đó tắt riêng ItemGuard WorldGuard support trong clone, vì WorldGuard permission contract đã có controlled journey riêng. WorldGuard plugin vẫn được load; production không đổi.

### Inconclusive timing attribution

Attempt tiếp theo tạo publication `ABORTED/SOURCE_CHANGED`, nhưng entity từ attempt trước còn persisted và đi vào reserve trước sau restart. Do đó observed journal window không thể quy exact cho entity hiện tại. Attempt được ghi `INCONCLUSIVE_TIMING_ATTRIBUTION` và không dùng làm primary proof.

### Carryover publication

Entity stale `d01440c3-f3dc-4868-b2c9-2aa5f6b1d83d` nằm trong unloaded chunk. Cleanup command trước tokenized run thấy `ABSENT` vì chunk chưa load. Khi bot load chunk ở tokenized run, entity này được publish hợp lệ trước exact negative fixture:

- publication `9b2aeba2-b095-41bb-8bd8-1d500c5a69e8`;
- code/item UUID `E9NZIZ / a9d7b7ae-fc84-4b68-9f86-fd0aeb4c46fa`;
- state `PUBLISHED`;
- canonical/snapshot `1/1`.

Carryover làm aggregate tăng `+1`, nhưng không match exact tokenized trigger name/source UUID và có `created_at` trước exact negative row. Evidence giữ nguyên row này, không xóa lịch sử hợp lệ và không dùng aggregate delta làm negative verdict.

## Verification

Java 21 (`C:/Program Files/Java/jdk-21`):

Focused:

```text
./mvnw.cmd -Dtest=EntityPublicationLifecyclePolicyTest,AsyncTagPublicationCoordinatorTest,IdentityReadinessCoordinatorTest,TagPublicationRepositoryTest test --no-transfer-progress
```

- `24/24` PASS.

Full:

```text
./mvnw.cmd clean test package --no-transfer-progress
```

- `58` suites;
- `201` tests;
- failures/errors/skips `0/0/0`;
- `BUILD SUCCESS`.

Clean-build/runtime comparison:

- `400/400` non-manifest JAR entries byte-identical;
- exact runtime candidate được khôi phục vào `target/ItemGuard-1.0.0.jar`;
- target SHA-256 `189022b7...7e10`.

Unit/build evidence không thay thế controlled Paper evidence; hai tầng được ghi riêng.

## Không được claim

Gate này không chứng minh:

- entity source-loss sau valid physical write/receipt;
- entity unload/chunk unload trong publication window;
- item entity merge/split hoặc stackable amount behavior;
- hopper/container/entity transfer;
- nhiều entity hoặc nhiều player đồng thời;
- scale/performance/soak;
- reclaim coverage cho unloaded entities;
- production behavior hoặc release readiness.

## Production

- Production source/JAR/server không bị sửa hoặc restart.
- Clone-only trigger và probe không được copy sang production.
- Không cần production code change theo exact controlled result này.
- Overall status giữ `NOT RELEASE READY`.

## Evidence files

- `E:\AI.WORK\itemguard-paper-smoke\entity-source-loss-negative-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-source-loss-positive-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-source-loss-final-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-source-loss-inconclusive-attempt.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-source-loss-negative-tokenized-bot.log`
- `E:\AI.WORK\itemguard-paper-smoke\entity-source-loss-positive-bot.log`
- `E:\AI.WORK\itemguard-paper-smoke\entity-source-loss-restart-bot.log`
- archive manifest tại đường dẫn đã nêu ở trên.
