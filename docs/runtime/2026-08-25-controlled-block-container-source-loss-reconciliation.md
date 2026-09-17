# Controlled block-container source-loss reconciliation

Ngày: 2026-08-25

## Kết luận

`VERIFIED controlled Paper` cho scope hẹp exact isolated single chest:

1. Production first-tag tạo đúng một publication `PREPARED` cho stable
   `BLOCK_CONTAINER_SLOT`; exact tagged source sau đó bị xóa và được graceful
   persist xuống region file trước restart.
2. Offline full-world NBT proof và prestart seal chứng minh source thật sự absent;
   restart không tự publish mù, không tạo ghost canonical/snapshot và không cấp
   item qua reclaim.
3. Negative result được seal trước restore, giữ nguyên publication ID.
4. Positive control restore exact bytes vào cùng world/block/slot; scheduled
   container scan chuyển chính publication đó sang `PUBLISHED`, tạo đúng một
   canonical + snapshot và durable exact `CONTAINER` observation sau `restoredAt`.
5. Graceful stop cuối đóng ItemGuard DB, save mọi dimension; ports đóng,
   `session.lock` lấy exclusive; offline NBT thấy đúng một item restored ở slot 3
   với exact code + item UUID.

Đây không phải bằng chứng atomic Bukkit + SQLite. Overall vẫn
`NOT RELEASE READY`; production untouched.

## Environment và artifact

- Controlled clone: `E:\AI.WORK\itemguard-paper-smoke`.
- Paper: `1.21.11-131`; Java `21.0.4`.
- Minecraft port `57484`; query port `36103`.
- ItemGuard candidate: `target/ItemGuard-1.0.0.jar`;
  SHA-256 `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`.
- Clone-only probe SHA-256:
  `09ac3963d37bec3518d7ec4ba2e068596286ef14ebf22b578567b599472b0af7`.
- Java 21 trước runtime: `58` suites / `201/201` PASS.
- Production source/candidate không đổi trong fixture RCA; chỉ clone probe bỏ
  stale `BlockState.update(true,false)` vốn overwrite live container bằng empty
  snapshot. Delta review `ag/claude-opus-4-6-thinking`: `VERDICT: PASS`.

## Boundary được giữ

- Không recapture baseline.
- Không reinstall controlled trigger.
- Không restore backup.
- Không deploy/restart/mutate production.
- Trigger được gỡ offline đúng một lần sau negative absence seal.
- `anti-dupe.enabled=false`; `reclaim.issuance-enabled=false` suốt journey.

## Sealed precondition trước restart

- Run token: `a50fffd9-ae62-41b8-8c39-a6b1a77e57f9`.
- Code: `851LW9`.
- Item UUID: `4dcc0ef4-5057-4257-86f5-596e55b8d8af`.
- Publication ID: `c07df7d6-15ed-4add-937e-97b79927cb3f`.
- Source:
  `BLOCK_CONTAINER_SLOT:34486cdf-24f0-4ade-9f92-f820b9605e20:-5:-60:10:3`.
- Locator: isolated chest `world UUID 34486cdf-...`, block `-5,-60,10`, slot `3`.
- Tagged serialized SHA-256:
  `1c5a8301cf517aaadcbad0a990a072485a54b3fc6234b62196852dd0ec6b736b`.
- Exactly one `PREPARED`; canonical/snapshot `0/0`; integrity `ok`.
- Full-world offline parser read `1,343` region/entity chunks across all three
  dimensions; target chest `Items=[]`, four horizontal neighbors non-chest,
  code/UUID hits `0/0`, unknown component storage absent.
- Prestart manifest target region SHA-256:
  `62fe1a2dc66fa078112b3f57a75658a60f183e04eb43d5a99566e08f433b865d`.

## Negative restart

1. Offline drop-trigger oracle yêu cầu exact sealed publication, `PREPARED`,
   canonical/snapshot `0/0`, rồi read-back trigger `1 -> 0`.
2. Wrapper `itemguard_container_source_loss_launch.py source-absent` chạy
   `prestart_seal.py` ngay trước Java 21. Seal rehash toàn manifest, reject file
   changed/missing/new và xác nhận same publication/canonical/snapshot/trigger.
3. Negative bot xác nhận exact chest slot empty sau restart:
   `ITEMGUARD_CONTAINER_SOURCE_LOSS_ABSENT 851LW9 ...`.
4. `/matdo sos 851LW9` trả `ID chua duoc theo doi.`; không thấy issuance marker.
5. Read-only DB oracle:
   - same publication ID vẫn `PREPARED`;
   - publication digest giữ exact SHA trên;
   - canonical/snapshot `0/0`;
   - tracked/snapshot totals giữ baseline `504/504`;
   - publications `344` (= baseline `343` + publication fixture);
   - reclaim claims giữ `10`, active `0`;
   - trigger `0`, schema `7`, integrity `ok`.
6. Kết quả negative được ghi và copy read-back trước bất kỳ restore nào:
   `E:\AI.WORK\backups\itemguard-container-source-loss-negative-result-evidence.json`;
   SHA-256 `b71bc54366a357d2e05624aa7841f9ab0c19c86780da8cf0d16643f0a6400234`.

## Positive exact-source control

1. Probe deserialize exact saved bytes, restore vào cùng chest/block/slot; fresh
   container read-back trả exact serialized SHA.
2. Chest được giữ open hơn một configured scheduled scan interval.
3. Exact live read-back sau hơn 35 giây trả same code/UUID/SHA.
4. Read-only DB oracle:
   - same publication ID chuyển `PREPARED -> PUBLISHED`;
   - `updated_at=1787596057797 >= restoredAt=1787596053828`;
   - one canonical row và one snapshot v1, payload `234` bytes;
   - physical/publication/snapshot SHA đều exact
     `1c5a8301...6b736b`;
   - durable exact observation:
     `CONTAINER/BLOCK:34486cdf-...:-5:-60:10/slot3`,
     epoch `1787596087789`, observed `1787596087790`, đều sau `restoredAt`;
   - tracked/snapshot totals `505/505`, publications `344`;
   - reclaim claims giữ `10`, active `0`; trigger `0`; integrity `ok`.
5. Positive DB/bot evidence được seal trong final artifact:
   `E:\AI.WORK\backups\itemguard-container-source-loss-final-evidence.json`;
   SHA-256 `4c57709ecdf877f044c6b09a5886631181108a3d7173a754712d5dbd7dfb4be0`.

## Graceful stop và offline positive NBT

- Log ordering exact run:
  run token -> `Done` -> restore -> present -> `Stopping server` ->
  `Database connection closed` -> `All dimensions are saved`.
- Ports `57484/36103` đóng; world `session.lock` lấy exclusive; `level.dat`
  mtime sau `restoredAt`.
- Exact listener Java process không còn giữ ports; wrapper tracking session còn
  treo sau save và được đóng sau khi evidence offline đã seal. Không dùng điều
  này để mở rộng claim về mọi Moonrise/JVM-exit scenario.
- Offline NBT sau stop đọc target region
  SHA-256 `99a15c400aad19693a038749c8eb306cf7b4af662d6eba58de990933ece2a5fd`:
  đúng một chest item ở slot 3; exact `itemguard:code=851LW9` và
  `itemguard:item_uuid=4dcc0ef4-...` mỗi giá trị xuất hiện đúng một lần trong item.
- Offline positive region evidence:
  `E:\AI.WORK\backups\itemguard-container-source-loss-positive-region-evidence.json`;
  SHA-256 `b73d02183b1fcefb264ec08e0589bb8ca83f0474a9ab3e4ce35c73630648b495`.
- Final runtime log:
  `E:\AI.WORK\backups\itemguard-container-source-loss-final-20260825-014026.log`;
  SHA-256 `f2bf0643311d2d9645dbcc3d50cce42116984e89bb80a3c431aff3f2b62d17cb`.

## Scope

`VERIFIED controlled`:

- exact non-stackable item trong isolated single chest;
- source removed before valid reconcile receipt, graceful disk persistence và
  startup source-absence;
- no ghost canonical/snapshot, detached reclaim denial, no issuance;
- continuity của exact publication ID qua negative -> positive;
- exact same world/block/slot byte restore;
- scheduled exact container observation và physical/publication/snapshot digest;
- graceful DB/world save và offline positive chest PDC persistence.

`NOT VERIFIED`:

- source mất sau valid main-thread receipt nhưng trước SQLite commit;
- crash/force-kill trong block-container windows khác;
- relocation sang source key khác;
- double chest, stackable item, hopper/dropper/piston và container type khác;
- unloaded/forced chunks, concurrent players và multi-world race;
- entity/external storage source-loss;
- destructive quarantine, issuance, scale/backpressure và production.
