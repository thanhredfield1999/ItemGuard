# Controlled post-receipt unclean-stop recovery

Ngày: 2026-08-25

## Kết luận

`VERIFIED controlled Paper` cho scope hẹp exact player slot:

1. Exact publication `PREPARED` được reconcile bằng valid receipt chứa code,
   item UUID, `PLAYER_SLOT` source key và digest exact tagged bytes.
2. Clone-only probe xóa slot 8 và `Player.saveData()` khi reconcile transaction
   vẫn đang trong exact delayed window.
3. Watcher chỉ chấp nhận exact run token, controlled root, Java 21 executable,
   Java PID, marker, physical digest và rollback-journal file; Java child dừng
   không sạch sau removal `68 ms`.
4. Restart recovery trả same publication về `PREPARED`, canonical/snapshot `0/0`,
   DB integrity `ok`; playerdata vẫn không có source, reclaim trả unknown ID và
   không issuance.
5. Negative evidence được seal trước trigger removal hoặc physical restore.
6. Exact 231 bytes được restore vào cùng player/slot; same publication chuyển
   thành `PUBLISHED`, tạo đúng một canonical + snapshot exact digest.
7. Graceful stop cuối đóng DB/save dimensions; offline NBT thấy đúng một exact
   restored item ở slot 8.

Không có production defect được chứng minh và không có production code change.
Overall vẫn `NOT RELEASE READY`; production untouched.

## Environment và artifacts

- Controlled clone: `E:\AI.WORK\itemguard-paper-smoke`.
- Paper `1.21.11-131`; Java `21.0.4`.
- Ports `57484/36103`.
- ItemGuard candidate SHA-256:
  `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`.
- Clone-only probe SHA-256:
  `486d2743e83810e5daa81fa033e09d8f8825b1b52db61d0eff057af57c5128c8`.
- Pre-runtime backup:
  `E:\AI.WORK\backups\itemguard-paper-smoke-post-receipt-crash-pre-runtime-20260825-074924.tar.gz`,
  SHA `4882cf7a6b3040860db693f783be8d1f5c9afe4b087c566aa43e0fed9253430b`.
- `anti-dupe.enabled=false`; `reclaim.issuance-enabled=false`.

## Baseline

- schema `7`, integrity `ok`, journal mode `delete`;
- tracked/snapshot/publication `506/506/345`;
- claims `12`, `PREPARED=0`, active claims `0`, trigger `0`;
- DB SHA `c114c60d…ec0e0`; ports đóng.

## Exact fixture

- Run token: `98462cb9-e067-4d49-b6ba-62351e749c22`.
- Code: `UAYB03`.
- Item UUID: `9d97a539-964f-4246-8694-51ef31bd6c9b`.
- Player UUID: `71fcf6be-e1ee-3c92-9a42-893cbe44818c`.
- Slot: `8`.
- Publication ID: `c96b7113-635f-4708-b1bb-b16ff30f604d`.
- Source key:
  `PLAYER_SLOT:71fcf6be-e1ee-3c92-9a42-893cbe44818c:8`.
- Serialized bytes: `231`.
- SHA-256:
  `db62fc481756b963cddf03e32e7e0847ea816dcc1d2363934803f0f7655866df`.

## Phase 1 — durable PREPARED

Controlled trigger giữ first publication transition. Production first-tag path tạo:

- exactly one `PREPARED` publication;
- canonical/snapshot fixture `0/0`;
- totals `506/506/346`;
- slot 8 trong offline playerdata chứa exact code + UUID;
- graceful stop: `Database connection closed`, `All dimensions are saved`.

Phase-1 evidence:
`E:\AI.WORK\backups\itemguard-post-receipt-crash-phase1-evidence.json`,
SHA `309613e8921f6da8b17bcefb6db94fe77bbd211670fb47ca375dcf3b61f0a5d4`.

## Phase 2 — exact delayed transaction và unclean stop

Offline trigger replacement chỉ áp dụng publication ID trên, giữ state
`PREPARED` và chạy bounded recursive CTE để mở rộng transaction window.

Probe yêu cầu trên Paper main thread:

- exact code/UUID ở same slot;
- exact physical SHA;
- rollback-journal path tồn tại, size > 0 trước removal;
- slot được clear và `Player.saveData()`;
- journal path vẫn tồn tại, size > 0 sau save.

Timeline:

- journal observed: `1787623447365`;
- source removed/saved: `1787623447407`;
- Java child stopped: `1787623447475`;
- delay removal → stop: `68 ms`.

Watcher chỉ tác động Java PID `44180` sau exact executable/root/token/marker checks.
Raw DB, journal và playerdata hashes giống hệt trước/sau stop:

- DB SHA `6266d178…28955`;
- journal size `29240`, SHA `f4b42486…842c54`;
- playerdata SHA `8a07ab1f…2bb04`;
- physical SHA exact `db62fc48…5866df`.

Lưu ý evidence: journal header 8 byte bằng zero cả trước và sau stop. Không dùng
header zero riêng lẻ để claim transaction state. Target-window proof là tổng hợp
exact trigger + marker trước/sau save + stop 68 ms + immutable raw files + trạng
thái recovery sau restart.

Stop evidence:
`E:\AI.WORK\itemguard-paper-smoke\post-receipt-crash-stop-evidence.json`,
SHA `742cc8adc0536a5c8356dd892b9d09f44651d8fdfc3899ae2536d9aebd4165b1`.
Raw before/after copies:
`E:\AI.WORK\backups\itemguard-post-receipt-crash-hot-98462cb9-e067-4d49-b6ba-62351e749c22`.

## Negative restart — sealed trước restore

Prestart exact hash seal xác nhận raw DB/journal/playerdata/marker/physical bytes
không đổi. Sau startup:

- same publication ID vẫn `PREPARED`;
- canonical/snapshot fixture `0/0`;
- total `PREPARED=1`;
- claims giữ `12`, fixture claims `0`, active claims `0`;
- DB schema 7, integrity `ok`;
- offline NBT và reconnect inventory đều không có source;
- `/matdo sos UAYB03` trả `ID chua duoc theo doi.`;
- không issuance.

Negative evidence được copy và hash trước trigger removal/restore:
`E:\AI.WORK\backups\itemguard-post-receipt-crash-negative-evidence.json`,
SHA `de650fa5c88e9e9536b4cf9baabaaa40ef544c5f8a659420bed6eacb9ef31bd5`.

Sau seal, exact controlled trigger được gỡ offline; publication vẫn `PREPARED`,
canonical/snapshot vẫn `0/0`.

## Positive same-source recovery

Clone-only probe deserialize exact 231 bytes và restore cùng player UUID/slot 8.
Readback physical item giữ exact code/UUID/SHA. Login anchored reconcile path chuyển:

- same publication ID `PREPARED -> PUBLISHED`;
- canonical +1, snapshot v1 +1;
- snapshot payload 231 bytes;
- publication/snapshot/physical SHA exact `db62fc48…5866df`;
- totals `507/507/346`;
- `PREPARED=0`, trigger `0`, active claims `0`;
- claims giữ `12`, fixture claim `0`.

Không có observation ledger row cho fixture trong positive phase là expected source
contract: login scan gọi `scanPlayerInventory(player)` với `scanEpoch=null`; nó
reconcile publication nhưng chỉ scheduled completed epoch mới ghi observation.

Graceful stop cuối đóng DB và save dimensions; ports đóng. Offline NBT có đúng
một item slot 8 với exact code/UUID. Final DB integrity `ok`, SHA
`2f9438ce…bd2a`.

Final evidence:
`E:\AI.WORK\backups\itemguard-post-receipt-crash-final-evidence.json`,
SHA `728c7c8caf5d4874b16f31e53cb4b8ffbd4fd4a390bbe7e030ede3c063c6c5dc`.
Final log SHA `fa97ace8d16e2fbac500b11e6492245fc22dee895faf5f280cdcec6b335e5664`.

## Verification và review

Focused Java 21:

```text
.\mvnw.cmd -Dtest=IdentityReadinessCoordinatorTest,TagPublicationRepositoryTest,TagReconciliationReceiptTest,SqliteConnectionOwnerTest test --no-transfer-progress
```

Kết quả `22/22` PASS.

Full Java 21:

```text
.\mvnw.cmd clean test package --no-transfer-progress
```

Kết quả `58` suites / `201/201` PASS; `BUILD SUCCESS`; `git diff --check` PASS.
Clean JAR SHA `67302938…ed9d4` và runtime candidate có `400/400` non-manifest
entries giống byte; `target` đã khôi phục exact runtime bytes `189022b7…7e10`.

Independent reviewer `cc/claude-opus-4-8`:

- `VERDICT: PASS`;
- findings none;
- `PRODUCTION CHANGE REQUIRED: NO`.

Review: `docs/reviews/2026-08-25-post-receipt-crash-recovery-opus-review.md`.

## Scope

`VERIFIED controlled`:

- exact non-stackable player slot;
- source removed/saved after valid receipt while delayed reconcile active;
- one unclean Java stop at a 68 ms sampled point;
- restart rollback to `PREPARED/canonical0/snapshot0`;
- fail-closed unknown-ID reclaim/no issuance;
- exact same-player/same-slot byte recovery;
- exactly one canonical/snapshot/PUBLISHED result;
- graceful final persistence.

`NOT VERIFIED`:

- other timing points or repeated stress runs;
- concurrent/multi-player publication;
- entity/container post-receipt variants;
- relocation, double chest, stackable, hopper/dropper/piston;
- external absence/issuance, scale/backpressure và production.
