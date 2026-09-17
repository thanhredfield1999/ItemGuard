# Controlled multi-source custom-stack merge — 2026-08-27

## Kết luận

`VERIFIED controlled Paper PASS` cho bounded same-tick multi-source custom-stack ground merge trên Paper `1.21.11-131`, Java `21`.

Gate này **không** chứng minh multi-thread/multi-server writer, crash/unload stress, production scale hoặc release readiness. Overall vẫn `NOT RELEASE READY`; production không bị deploy/restart/mutate.

## Candidate và review

- Runtime ItemGuard JAR SHA-256: `8c2ebefa203e13577ab72977be3bf29642d1089414a0e9d9e3a5a135d26a7cf3`.
- Runtime probe SHA-256: `a1ec8342949e64e4e16acdc5878907b5893210cc6335bc3939d896644f4b9a93`.
- Authoritative run token: `9dfa334c-a9a4-4cab-9d3d-83abd12f326c`.
- Exact Opus 4.8 pre-runtime review: `PASS_FOR_CONTROLLED_RUNTIME`.
- Exact Opus 4.8 runtime-informed correction review: `PASS_FOR_CONTROLLED_RERUN`.
- Post-runtime clean rebuild tạo whole-JAR SHA khác do ZIP metadata (`310ebcf4…a267` / `8c45e1bf…35a3`), nhưng entry comparison có `351/351` ItemGuard và `69/69` probe entry bytes giống hệt, zero differing entries. Runtime claims chỉ gắn với exact runtime JAR hashes ở trên.

## Defect được runtime phát hiện và sửa

Attempt đầu dùng token đã retire `6e2e6a64-6288-490c-b05f-0641a61ef5a5` phát hiện product defect thật:

- fixture đặt `itemguard:code` và `itemguard:item_uuid` bằng `PersistentDataType.INTEGER`;
- `ItemTrackingService.resolveIdentityTags` gọi `pdc.get(key, STRING)` ngay sau type-agnostic `pdc.has(key)`;
- Paper ném `IllegalArgumentException: The found tag instance (IntTag) cannot store String`;
- `ItemMergeEvent` handler không cancel được và bốn wrong-type entity coalesce;
- server vẫn clean-stop; DB logical/file SHA exact baseline, integrity `ok`.

TDD correction:

1. Regression compile RED chứng minh thiếu type-aware resolver path.
2. Adapter kiểm `pdc.has(key, STRING)` trước mọi typed `get`.
3. Key tồn tại nhưng không phải STRING được resolver phân loại `CORRUPT`.
4. `hasCodeOrUuid` coi mọi trạng thái khác `ABSENT` là protected, nên merge cancel fail-closed.
5. Probe cũng không đọc STRING trên wrong-type PDC.

Incident: `docs/incidents/2026-08-27-wrong-pdc-type-ground-merge-bypass.md`.

## Ma trận runtime authoritative

Fixture drop `34 entity / 37 item`, custom max stack size `8`, pickup enabled, finite pickup delay và `willAge=true` để tránh zero-event test:

| Category | Spawn | Kết quả | Event proof |
|---|---:|---:|---|
| untagged control | 5 × x1 | 1 × x5 | 4 event, 0 cancellation |
| COMPLETE | 5 × x1 | giữ 5 | LOWEST un-cancelled; HIGHEST/MONITOR tất cả cancelled |
| code-only | 4 × x1 | giữ 4 | source/target UUID + status đều có evidence |
| UUID-only | 4 × x1 | giữ 4 | source/target UUID + status đều có evidence |
| malformed UUID | 4 × x1 | giữ 4 | fail-closed `CORRUPT` |
| wrong PDC datatype | 4 × x1 | giữ 4 | fail-closed; zero exception/coalesce |
| mixed amounts | x1/x2/x3 | giữ 3, total 6 | exact amount multiset + UUID preservation |
| component mismatch | 2 × x1 | giữ 2 | zero `ItemMergeEvent` negative control |
| protected + two untagged | 3 entity / 3 item | protected x1 + untagged x2 | đúng 1 live untagged merge |

Final authoritative state: `29 entity / 37 item`, `controlSuccessfulMerges=4`, `eventTelemetryCaptured=true`, `eventEndpointCoverage=true`. Sáu protected event categories có source và target endpoints; toàn bộ protected UUID/PDC datatype/value/amount giữ nguyên.

## Restart và cleanup

- Clean stop sau prepare/verify có `Database connection closed` và `All dimensions are saved`.
- Clean restart xác nhận exact `29 entity / 37 item`, same entity UUIDs, amounts, item components và PDC values/types.
- Restart marker ghi rõ `eventTelemetryCaptured=false`, `eventEndpointCoverage=false`; không tái sử dụng event telemetry cũ.
- Một diagnostic bot RED được giữ vì oracle nhầm state-derived `controlSuccessfulMerges=4` thành event counter `0`; product và restart marker đều đúng. Oracle được sửa rồi restart rerun PASS.
- Cleanup xóa đúng `29 entity / 37 item` và location marker.
- Offline NBT scanner parse `33` entity chunks / `46` entities: zero hit cho `34` fixture entity UUID, run token và PDC fixture key.
- DB post-stop có integrity `ok`; file SHA và logical dump SHA exact baseline.
- Ports `57485/36104` đóng.

## Source/build verification

- Regression RED: `IdentityTagResolverTest.wrongPersistentDataTypeIsRejectedBeforeTypedRead` thiếu overload.
- Focused Java 21: `21/21 PASS`.
- Full Java 21: `269/269 PASS`, `BUILD SUCCESS`.
- Probe clean package: PASS.
- `git diff --check`: PASS.
- Audit sibling identity reads: hai identity keys chỉ được typed-read tại guarded `ItemTrackingService` adapter; các caller khác dùng service/resolver.

## Evidence và restore

- Evidence directory: `E:/AI.WORK/evidence/itemguard-multisource-merge-20260827-203532`.
- Sealed archive: `E:/AI.WORK/evidence/itemguard-multisource-merge-20260827-203532.tar.gz`.
- Archive SHA-256: `cfa002a54ec20e74736f2368336d90bc9a02d0498803b5ed610afaa5a82f62c4`.
- Manifest SHA-256: `462ba59a14bb134e0256933321bba23532ca97eb55321f9dbecb2390221566a6`.
- Independent archive read-back: `45` manifest files / `51` tar members, no links/path traversal, all hashes match.
- Backup SHA-256: `f0472a715142f42234273bbc109e81359c29144346360ee8b1ab87648ae0c544`.
- Clone restored byte-exact baseline and offline:
  - ItemGuard `5fc2512f…8e3c`;
  - probe `0fd75b88…5e03`;
  - DB `86bf625f…7eee`, logical SHA exact baseline.

## Remaining scope

- Multi-thread/multi-server/multi-writer persistence contention.
- Concurrent crash/unload stress.
- Hopper minecart/double-hopper/double-chest variants and mid-transfer unload.
- Offline/disconnect timing, relocation/tombstone recovery, non-chest container and production scale.
- Production deploy/restart/verification requires separate explicit approval.

## Notion và checkpoint

- Existing Notion task được append đúng một marker `[ITEMGUARD_MULTISOURCE_MERGE_20260827_203532]` sau khi phân trang toàn bộ; read-back `180` blocks / `2` pages, marker count `1`.
- Status giữ `In Progress`, Updated `2026-08-27`; không tạo page trùng và không đánh dấu release/deployed.
- Receipt: `E:/AI.WORK/evidence/itemguard-multisource-merge-20260827-203532-notion-receipt.json`.
- Attempt cập nhật `.hermes/WORKING_STATE.md` bị protected-file policy chặn do approval timeout; attempt không sửa file và không được retry/lách guard. `CURRENT_STATE.md`, risk register, runtime report và incident là current reporting artifacts đã cập nhật.
