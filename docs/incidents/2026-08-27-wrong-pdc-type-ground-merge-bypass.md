# Incident: wrong PDC datatype escaped ground-merge cancellation

Ngày: 2026-08-27  
Môi trường phát hiện: controlled Paper `1.21.11-131`, Java `21`  
Production impact: `NOT OBSERVED`; production không bị chạm.

## Triệu chứng

Bounded multi-source fixture tạo bốn `MINECART` stack giống nhau với:

- `itemguard:code` = `PersistentDataType.INTEGER`;
- `itemguard:item_uuid` = `PersistentDataType.INTEGER`.

Khi Paper phát `ItemMergeEvent`, ItemGuard log:

```text
IllegalArgumentException: The found tag instance (IntTag) cannot store String
at PaperPersistentDataContainerView.get(...)
at ItemTrackingService.resolveIdentityTags(...)
at ItemListener.onItemMerge(...)
```

Handler không đi tới `event.setCancelled(true)` và bốn wrong-type entity coalesce. Fixture fail-closed với `ITEMGUARD_MULTISOURCE_MERGE_REJECTED coalesced-wrong_type`.

## Root cause

`PersistentDataContainer.has(NamespacedKey)` chỉ xác nhận key tồn tại, không xác nhận datatype. Paper `1.21.11` không trả `null` khi gọi `get(key, STRING)` trên `IntTag`; API ném `IllegalArgumentException` trước khi `IdentityTagResolver` có thể phân loại trạng thái `CORRUPT`.

Fix trước runtime chỉ thêm presence-aware resolver nhưng vẫn typed-read quá sớm, nên unit test giả định `null` không phản ánh contract Paper thật.

## Regression test

- `IdentityTagResolverTest.wrongPersistentDataTypeIsRejectedBeforeTypedRead` được thêm trước fix và RED vì chưa có type-aware resolver overload.
- `GroundItemMergeWiringContractTest` khóa cả:
  - type-agnostic `pdc.has(key)`;
  - datatype guard `pdc.has(key, PersistentDataType.STRING)`;
  - typed read chỉ sau guard.

## Fix

- `ItemTrackingService.resolveIdentityTags` tính riêng presence và STRING compatibility.
- Chỉ gọi `pdc.get(key, STRING)` khi `pdc.has(key, STRING)` đúng.
- `IdentityTagResolver` nhận presence/type/value; key tồn tại sai type trả `CORRUPT`.
- `hasCodeOrUuid` giữ fail-closed: mọi status khác `ABSENT` đều protected.
- Controlled probe áp cùng safe-read rule để oracle không tự ném exception.

## Verification

### Unit/build

- Focused `21/21 PASS`.
- Full Java 21 `269/269 PASS`, `BUILD SUCCESS`.
- Probe clean package PASS.
- `git diff --check` PASS.

### Controlled Paper

Authoritative token `9dfa334c-a9a4-4cab-9d3d-83abd12f326c`:

- four wrong-type entities giữ nguyên `4 × x1`;
- LOWEST thấy un-cancelled attempts;
- HIGHEST và MONITOR thấy tất cả protected attempts cancelled;
- cả source/target event endpoint được ghi;
- zero `IllegalArgumentException`, `Could not pass event`, server-thread ERROR, SEVERE hoặc fixture rejection;
- exact wrong-type PDC INTEGER values `101/202` và entity UUIDs giữ qua clean restart;
- cleanup/NBT absence PASS;
- DB file/logical SHA exact baseline, integrity `ok`.

Opus 4.8 runtime-informed correction review: `PASS_FOR_CONTROLLED_RERUN`.

## Evidence

- Runtime report: `docs/runtime/2026-08-27-controlled-multisource-custom-stack-merge.md`.
- Sealed archive: `E:/AI.WORK/evidence/itemguard-multisource-merge-20260827-203532.tar.gz`.
- Archive SHA-256: `cfa002a54ec20e74736f2368336d90bc9a02d0498803b5ed610afaa5a82f62c4`.
- Attempt RED lịch sử được giữ tại `failed-attempt-wrong-type/`; không đổi nhãn thành PASS.

## Runtime verification status

`VERIFIED CONTROLLED PAPER` cho exact candidate/runtime matrix trên Paper `1.21.11-131`.

Không chứng minh production, multi-server/multi-writer hoặc mọi caller/PDC key tùy ý. Production vẫn `NOT DEPLOYED / NOT VERIFIED`.
