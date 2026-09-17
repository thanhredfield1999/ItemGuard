# Controlled post-receipt source-loss semantics

Ngày: 2026-08-25

## Kết luận

`VERIFIED controlled Paper` cho scope hẹp exact player slot:

- `TagReconciliationReceipt` là proof point-in-time: exact code, item UUID,
  `PLAYER_SLOT` source key và SHA-256 của tagged bytes tồn tại khi receipt được mint.
- Sau valid receipt, source bị xóa và `Player.saveData()` persist slot trống khi
  SQLite reconcile transaction vẫn đang mở.
- Same publication sau đó commit `PUBLISHED`; canonical và snapshot được ghi
  atomically với exact digest.
- Sau graceful stop và restart, playerdata vẫn không có source, không có durable
  observation sau thời điểm removal và reclaim vẫn fail-closed/no issuance.

Đây là expected point-in-time semantics, không phải defect production. Không có
production code change. Overall vẫn `NOT RELEASE READY`; production untouched.

## Environment và artifact

- Clone: `E:\AI.WORK\itemguard-paper-smoke`.
- Paper `1.21.11-131`, Java `21.0.4`.
- Minecraft/query ports `57484/36103`.
- Runtime candidate SHA-256:
  `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`.
- Clone-only probe SHA-256:
  `870bba256bf3a86ea049c15e23f5193a318e3b89f2d14e2c13d314d7916b2c87`.
- `anti-dupe.enabled=false`; `reclaim.issuance-enabled=false`.
- Pre-runtime clone backup:
  `E:\AI.WORK\backups\itemguard-paper-smoke-post-receipt-pre-runtime-20260825-023120.tar.gz`,
  SHA-256 `8ad7800c5b4fc8ab0a67e453e356ccdb8b7801ab8dc086985cc05e2e2e221b5a`.

## Semantics và call path

1. Anchored player-slot scan đọc exact item trên Paper main thread và tạo
   `TagReconciliationReceipt` từ code, UUID, stable source key và tagged digest.
2. `IdentityReadinessCoordinator` gửi receipt sang serial SQLite owner.
3. Repository chỉ reconcile exact `PREPARED` row nếu code/UUID/sourceKey/digest
   cùng khớp, rồi trong một transaction upsert canonical, snapshot và đổi state
   thành `PUBLISHED`.
4. Bukkit inventory và SQLite không có distributed transaction. Receipt không
   phải lease giữ item tại locator đến commit; nó chứng minh source tại một thời
   điểm cụ thể.

## Controlled harness

Journey dùng hai restart phase, không sửa ItemGuard candidate:

1. Offline install trigger giữ first publish để tạo một exact `PREPARED` mà không
   materialize canonical/snapshot.
2. Graceful stop; offline thay trigger bằng `AFTER UPDATE` chỉ áp dụng exact
   publication ID. Trigger chạy recursive CTE khoảng 30 triệu iterations để mở
   rộng commit window nhưng không đổi dữ liệu.
3. Restart. Exact source tạo valid receipt và bắt đầu reconcile transaction trên
   serial DB thread.
4. Probe poll `itemguard.db-journal`; chỉ khi rollback journal tồn tại và size > 0
   mới dispatch removal về Paper main thread.
5. Trên main thread, probe revalidate exact item/PDC, xóa slot 8, gọi
   `Player.saveData()`, rồi yêu cầu journal vẫn tồn tại sau save. Sai một điều kiện
   thì fixture throw và không báo PASS.
6. Sau transaction hoàn tất, bot kiểm tra exact source vắng và DB oracle kiểm tra
   same publication/canonical/snapshot.

SQLite owner dùng one serial executor/one connection, `journal_mode=DELETE`,
`synchronous=FULL`, explicit commit/rollback. Vì trigger chạy trong exact
`PREPARED -> PUBLISHED` update, journal hiện diện trước removal và sau save là
bằng chứng transaction reconcile vẫn active trong cửa sổ đó.

## Evidence chính

Fixture:

- Code `DPYVQY`.
- Item UUID `1ecd0eb7-0de5-4790-97fb-624ee317139e`.
- Player UUID `71fcf6be-e1ee-3c92-9a42-893cbe44818c`, slot `8`.
- Publication ID `c6ab6cc3-10a9-4e44-bd26-235d14a8dca3`.
- Source key `PLAYER_SLOT:71fcf6be-e1ee-3c92-9a42-893cbe44818c:8`.
- Tagged SHA-256
  `e68e6aa5c2480a1f5c02474152074e0e783ddf75fd32332e9f7460378e5e1b93`.

Baseline offline:

- schema `7`, integrity `ok`;
- tracked/snapshot/publication `505/505/344`;
- `PREPARED=0`, reclaim claims `10`, active claims `0`, trigger `0`;
- ports đóng.

Phase 1:

- Exactly one new publication, state `PREPARED`;
- exact source key/code/UUID/digest;
- canonical/snapshot fixture `0/0`;
- totals `505/505/345`.

Phase 2 timeline:

- repository `updated_at=1787600256846`, được cấp trước transaction;
- journal observed `1787600256852`;
- source removed/saved empty `1787600256892`;
- `journalBeforeRemoval=true`, `journalAfterSave=true`.

Post-commit:

- same publication ID `PUBLISHED`;
- exact canonical row và snapshot v1 payload `229` bytes;
- publication/snapshot/physical digest giống nhau;
- totals `506/506/345`;
- zero `item_observations` cho fixture sau `removedAt`;
- schema `7`, integrity `ok`.

Durability/restart:

- offline NBT: inventory entries `0`, slot entries `0`, code/UUID absent;
- reconnect inventory diamond sword count `0`;
- exact source vẫn absent;
- hai `/matdo sos` tạo đúng hai claim `DENIED/PLAYER_VAULTS ... absence cannot
  be proven`; active claims `0`, không `ITEM_ISSUED` hoặc reclaim success;
- trigger gỡ offline, `PREPARED=0`, ports đóng;
- graceful logs có `Database connection closed` và `All dimensions are saved`.

## Verification

Focused Java 21:

```text
.\mvnw.cmd -Dtest=IdentityReadinessCoordinatorTest,TagPublicationRepositoryTest,TagReconciliationReceiptTest,SqliteConnectionOwnerTest test --no-transfer-progress
```

Kết quả: `22/22` PASS.

Full Java 21:

```text
.\mvnw.cmd clean test package --no-transfer-progress
```

Kết quả: `58` suites / `201/201` PASS; `BUILD SUCCESS`; `git diff --check` PASS.
Clean-build JAR SHA `719305b5…3de0` khác whole-file do ZIP metadata nhưng có
`318/318` non-META entries và zero byte-different entry so với runtime candidate.
`target` đã khôi phục exact runtime bytes `189022b7…7e10`.

Independent review:

- `cc/claude-opus-4-8` trả HTTP 429.
- Fallback đúng routing `ag/claude-opus-4-6-thinking` nhận source transaction,
  trigger/probe và sealed evidence; `VERDICT: PASS`, findings none,
  `PRODUCTION CHANGE REQUIRED: NO`.
- Review: `docs/reviews/2026-08-25-post-receipt-source-loss-opus-review.md`.

## Sealed artifacts

- Final evidence:
  `E:\AI.WORK\backups\itemguard-post-receipt-final-evidence.json`,
  SHA `2774f285b983329dd224d425534c4d4c61b9165edd78feca4f6af2e9b2ff4ce7`.
- Prepared evidence:
  `E:\AI.WORK\backups\itemguard-post-receipt-prepared.json`,
  SHA `78af1b80c1280e0fe611c6590a09eda79c7749a04a14ec9fd5ee695cfd1e70e7`.
- Result evidence:
  `E:\AI.WORK\backups\itemguard-post-receipt-result.json`,
  SHA `3a34131d7ec94221f82a02acb90996dd1e91089a7a393043d8d1a370534c77f8`.
- Delay-trigger evidence:
  `E:\AI.WORK\backups\itemguard-post-receipt-delay-trigger.json`,
  SHA `9db05ae829d9c8c3c6dd4021d9c786fd940192aafc6fa1e286ae55f50746b021`.
- Run bot evidence:
  `E:\AI.WORK\backups\itemguard-post-receipt-run-bot.log`,
  SHA `4f826c8e2340f4a67476c1e19107eb19bad0778bcb7fb6c4f5a15fc3b0ee43b8`.
- Phase 1/2 logs:
  `itemguard-post-receipt-phase1-2026-08-25-4.log.gz` SHA `64381cd5…664f9`;
  `itemguard-post-receipt-phase2-2026-08-25-5.log.gz` SHA `b6044c8a…e350`.
- Final restart log:
  `itemguard-post-receipt-restart-final-20260825-030150.log`,
  SHA `293c9de98751c238284f3adebd7454d87c4077ac4fb60f4856ca820d9056fa00`.

## Scope

`VERIFIED controlled`:

- exact non-stackable player slot;
- source mất sau valid receipt nhưng trước SQLite commit;
- point-in-time publication semantics;
- atomic DB canonical/snapshot/PUBLISHED write;
- durable physical absence, zero post-removal observations;
- fail-closed reclaim/no issuance;
- graceful stop/restart durability.

`NOT VERIFIED`:

- crash/force-kill sau removal nhưng trước commit;
- relocation/move semantics và same-tag copy ở locator khác;
- entity/container variant của post-receipt window;
- double chest, stackable, hopper/dropper/piston;
- multi-player, scale/backpressure và production.
