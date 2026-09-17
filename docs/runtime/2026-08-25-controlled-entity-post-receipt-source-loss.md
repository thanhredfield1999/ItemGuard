# Controlled entity post-receipt source-loss

Ngày: 2026-08-25

## Kết luận

`VERIFIED controlled Paper` cho scope hẹp một loaded ground-item entity sau durable reconciliation receipt:

1. Production first-tag tạo exact publication `PREPARED` và ghi identity vào exact entity.
2. Sau restart, clone-only fixture resolve và revalidate exact entity UUID, PDC identity và serialized SHA.
3. Exact reconciliation transaction bắt đầu; rollback journal chỉ dùng làm deterministic transaction-window marker.
4. Trong window đó, fixture revalidate exact UUID/PDC/SHA rồi remove entity trên Paper main thread.
5. Marker xác nhận journal tồn tại trước và sau removal; entity invalid và exact UUID không resolve ngay sau removal.
6. Same publication vẫn commit `PUBLISHED`; canonical và snapshot có exact digest đã chứng minh tại receipt time.
7. Sau trigger removal và graceful restart, exact entity vẫn absent; publication/canonical/snapshot bền vững.

Hành vi này là point-in-time receipt semantics, không phải atomic Bukkit+SQLite guarantee và không chứng minh source còn tồn tại tại commit time.

Không phát hiện production defect trong exact scope. Production source không đổi. Project tổng thể vẫn `NOT RELEASE READY`.

## Artifact identity

- ItemGuard runtime candidate SHA-256:
  `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`
- Java 21 clean-build JAR SHA-256:
  `1607082acbf7fcce757c8b0038c086075a6927ebc354a0a9e1e34f9604681dd7`
- Clean build/runtime comparison: `331/331` non-manifest entries byte-identical; whole-JAR hash khác do ZIP/manifest metadata.
- Clone-only probe SHA-256:
  `71ba635e73d90e8bc4406f0f357461512b7201d291b872c41a22ba8071cedeee`
- Final evidence SHA-256:
  `f79f6de6fb006d1997132fbb66c105d2401c9fbad0becba479093868d7e79c86`
- Phase-2 evidence SHA-256:
  `2343520f6923c2781a440a013aaf5394e741a4bf740704c96647aaa0ff3cbca6`
- Restart artifact log SHA-256:
  `aa1fbefc3443e52a77aa127e9c964244f3bb22cc4587f44300e8849b79310d9b`
- Archive manifest SHA-256:
  `f52e2eaafe95d6eca74bfbee9eee4e0289b1589fd6b2d7e458ff731fc5cd9c6e`
- Run token:
  `206a2b2b-a61c-4af4-90ca-eb57049c8ff2`

Archive:

`E:\AI.WORK\backups\itemguard-entity-post-receipt-source-loss-206a2b2b-a61c-4af4-90ca-eb57049c8ff2`

## Exact oracle

Fixture:

- attempt name: `ItemGuard Entity PostReceipt 206a2b2b`;
- code/item UUID: `B2L0LU / da116b55-e7ff-4973-878d-55e238a540be`;
- entity UUID: `e09ec250-2b09-4829-9d80-0404ca6198e9`;
- source key: `ENTITY:e09ec250-2b09-4829-9d80-0404ca6198e9`;
- publication ID: `ce5d274f-9279-4b5e-9439-c4f33a925a5d`;
- exact serialized payload: `235` bytes;
- exact serialized SHA-256:
  `cd74a8d97ffaa8bd0411a58bfcfba3f0d6a8f9bb35efcf5a66e3ca819abae445`.

### Phase 1 — exact PREPARED

Offline seal sau graceful stop:

- exact publication `PREPARED`;
- canonical count `0`;
- snapshot count `0`;
- publication SHA = physical marker SHA;
- totals tracked/snapshot/publication `511/511/354` sau publication reservation, với đúng một `PREPARED`;
- DB integrity `ok`;
- session lock exclusive.

Hold-first trigger được thay offline bằng exact reconciliation delay trigger. Trigger chỉ match exact publication transition.

### Phase 2 — source loss sau receipt

Orchestrator thực hiện server startup → exact pre-arm → bot join liên tục, loại bỏ latency giữa tool calls.

Exact marker:

- `journalObservedAt=1787676312067`;
- `removedAt=1787676312067`;
- `journalBeforeRemoval=true`;
- `journalAfterRemoval=true`;
- `validAfterRemoval=false`;
- `resolvableAfterRemoval=false`.

Fixture chỉ remove khi tất cả cùng match:

- exact entity UUID;
- exact PDC code/item UUID;
- exact serialized SHA;
- active rollback journal sau watcher arm.

Durable phase-2 seal sau ItemGuard DB close, dimensions save và ports close:

- same publication `PUBLISHED`, detail `null`;
- publication payload `235` bytes, exact SHA;
- đúng một exact canonical row;
- đúng một exact snapshot row với exact SHA/length;
- exact post-removal observations `0`;
- totals tracked/snapshot/publication `512/512/354`;
- `PREPARED=0`;
- active reclaim claims `0`;
- DB integrity `ok`.

### Live verify caveat

Phase-2 bot thấy source-loss marker rồi gọi verify sau 8 giây. Exact delay trigger vẫn giữ rollback journal active, nên command fail-closed với `VERIFY_FAIL journal-active`. Bot/wrapper exit nonzero; orchestrator `finally` sau đó dừng server. Đây không được dùng làm live post-commit oracle.

Primary phase-2 verdict không dựa bot exit. Nó dựa:

1. exact source-loss marker trong active same-publication transaction window;
2. graceful ItemGuard DB close + all dimensions saved + ports closed;
3. offline exact seal chứng minh same publication `PUBLISHED`, canonical/snapshot exact SHA và source-loss marker exact attribution.

### Transaction-timeline falsifier

Independent review vòng đầu coi `publication.updated_at` là commit timestamp và suy ra removal xảy ra sau commit. Giả định này sai: repository bind `updated_at` trong `UPDATE`, rồi exact `AFTER UPDATE` trigger chạy trước khi statement return và trước `connection.commit()`.

Falsifier dùng cùng `journal_mode=DELETE`, `synchronous=FULL` và trigger 30M chứng minh:

- `journalObservedAt > updatedAt`;
- update vẫn chưa finish;
- external reader vẫn thấy `PREPARED/updatedAt=0`;
- commit chỉ return khoảng 6.4 giây sau;
- final row mới là `PUBLISHED` với application-supplied `updatedAt` cũ.

Output SHA-256:
`6ef872ba99bd60358a33d02305b1aef19bddecd71e7b1ae748a24bef063cd6c5`.

Reviewer correction chấp nhận causal attribution trong controlled fixture: no-journal-at-arm + one serial SQLite owner + exact-only long trigger + journal xuất hiện sau receipt clock và giữ hơn 8 giây + exact UUID/PDC/SHA revalidation. Không có transaction thay thế cụ thể nào phù hợp toàn bộ observations và duration.

### Restart proof

Sau khi gỡ trigger offline, restart journey riêng gọi exact verify:

- `ITEMGUARD_ENTITY_POST_RECEIPT_ABSENT_AFTER_COMMIT B2L0LU da116b55-e7ff-4973-878d-55e238a540be`;
- `ITEMGUARD_ENTITY_POST_RECEIPT_RESTART_BOT_PASS`;
- ItemGuard `Database connection closed`;
- `All dimensions are saved`.

Journey được lặp lại sau khi sửa artifact logging; bot markers và server markers cùng tồn tại trong `entity-post-receipt-restart-orchestrator.log`.

Final offline state:

- schema `7`;
- DB integrity `ok`;
- tracked/snapshot/publication `512/512/354`;
- exact publication vẫn `PUBLISHED` với exact SHA;
- `PREPARED=0`;
- controlled triggers `0`;
- active reclaim claims `0`;
- post-removal observations `0`;
- controlled ports `57485/36104` đóng.

## Attempts không dùng làm primary proof

### `PYXFH0` — attribution failure

Publication `ddd1654f-...` commit `PUBLISHED`, canonical/snapshot được tạo nhưng exact source entity chưa từng bị remove. Startup watcher đã bắt một rollback journal không thuộc exact entity trước khi chunk load. Attempt được seal `INCONCLUSIVE_FIXTURE_ATTRIBUTION`; không phải PASS/FAIL production.

### `SR3V6B` — timing failure

Watcher pre-arm có bound 60 giây nhưng bot join bị chậm hơn do tool/notification latency. Watcher timeout trước exact chunk load; publication sau đó commit bình thường và source không bị remove. Attempt được seal `INCONCLUSIVE_FIXTURE_TIMING`; không phải PASS/FAIL production.

### Port collision không phải ItemGuard failure

Một retry chưa enable plugin vì `douyin_guard.exe` đang dùng local ephemeral port `57484`, làm Paper bind fail. DB giữ nguyên. Controlled clone được chuyển sang port rảnh `57485/36104`; process người dùng không bị kill hoặc sửa.

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

`git diff --check` PASS. Unit/build evidence và controlled Paper evidence được ghi riêng; không dùng tầng này thay thế tầng kia.

## Semantics

`TagReconciliationReceipt` là proof point-in-time của exact physical identity. Sau receipt hợp lệ, repository transaction có thể materialize canonical + snapshot + `PUBLISHED` dù Bukkit source mất trước commit. Bukkit entity/world state và SQLite không có distributed transaction.

Gate này chứng minh expected point-in-time behavior cho một exact loaded entity. Nó không mở issuance, không tạo item thứ hai và không chứng minh absence ở external stores.

## Independent review

Primary reviewer `cc/claude-opus-4-8`:

- vòng 1 `FAIL` do giả định sai `updated_at=commit time` và dùng final canonical state thay pre-transaction state;
- independent SQLite falsifier + source timeline bác bỏ hai giả định;
- correction review lật verdict sang `PASS`, findings none, production change `NO`;
- giữ gap: không có raw transaction-ID/callback binding; attribution là causal inference đủ cho exact controlled fixture, không phải direct production proof.

Review:
`docs/reviews/2026-08-25-entity-post-receipt-source-loss-opus-review.md`.

## Không được claim

Gate này không chứng minh:

- entity unload/chunk-unload trong publication window;
- entity merge/split hoặc stackable amount behavior;
- container post-receipt variant;
- relocation recovery;
- nhiều entity/player đồng thời;
- repeated timing/concurrency/backpressure/scale;
- external absence proof hoặc safe issuance;
- crash/force-kill sau source removal nhưng trước publication commit cho entity;
- production behavior hoặc release readiness.

## Production

- Production source/JAR/server không bị sửa, deploy hoặc restart.
- Clone-only trigger/probe/orchestrator không được copy sang production.
- Exact controlled result không yêu cầu production code change.
- Overall status giữ `NOT RELEASE READY`.

## Evidence files

- `E:\AI.WORK\itemguard-paper-smoke\entity-post-receipt-final-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-post-receipt-phase2-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-post-receipt-prepared-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-post-receipt-inconclusive-attribution.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-post-receipt-timing-inconclusive.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-post-receipt-restart-orchestrator.log`
- raw Paper logs `2026-08-25-23.log.gz`, `-24.log.gz`, `-25.log.gz` trong archive;
- archive manifest tại đường dẫn đã nêu ở trên.
