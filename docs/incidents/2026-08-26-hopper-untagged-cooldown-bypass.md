# Hopper untagged cooldown bypass

Ngày: 2026-08-26

## Trạng thái

`FIX VERIFIED CONTROLLED PAPER`; production `NOT DEPLOYED / NOT VERIFIED`.

## Triệu chứng có thể tái hiện

Với item vật lý hợp lệ chưa có identity trong source chest phía trên hopper:

1. `InventoryMoveItemEvent` đầu tiên bị hủy.
2. Handler cũ đánh dấu holder cooldown ngay trong nhánh hủy.
3. Retry trong 500 ms đi qua nhánh cooldown và không bị hủy.
4. Không nhánh nào request first publication cho exact source slot.

Kết quả: fresh eligible item có thể rời source bằng hopper trước khi có canonical identity.

## Root cause

`ContainerListener.onInventoryMoveItem` dùng cùng cooldown cho hai trách nhiệm khác nhau:

- quyết định có hủy transfer hay không;
- dedupe việc xử lý holder.

Cooldown bao quanh cancellation, nên retry có thể bypass fail-closed policy. Paper 1.21.11 không cung cấp source slot trực tiếp trong event và initiator có thể đã remove item trước khi event được gọi; không được đoán slot hoặc mutate destination/event item.

## Fix tối thiểu

- Thêm `HopperTransferPolicy`:
  - eligible untagged → `CANCEL_AND_SCAN_SOURCE`;
  - complete nhưng chưa ready/corrupt → `CANCEL`;
  - complete + ready → `ALLOW`;
  - ineligible untagged → `ALLOW`.
- Handler luôn `event.setCancelled(true)` trước mọi cooldown check cho `CANCEL_AND_SCAN_SOURCE`.
- Cooldown chỉ dedupe một `runTask` next-tick scan trên captured source inventory, sau khi Paper hoàn trả item vì cancellation.
- Không mutate `event.getItem()` hoặc destination inventory.

## Regression

- `HopperTransferPolicyTest`: 4 policy states.
- `HopperTransferWiringContractTest`: cancellation trước cooldown, source-only next-tick scan, no destination/event-item mutation, single-chest raw slot giữ local slot.
- RED ban đầu: compile fail vì `HopperTransferPolicy` chưa tồn tại.
- Focused corrected: `16/16` PASS.
- Full Java 21: `64` suites / `224/224`, failures/errors `0`.

## Runtime verification

Authoritative run `c1331bad-149f-4da8-9f36-55fdd9c18986` trên controlled Paper 1.21.11:

- fresh item untagged;
- untagged source attempt `1`, cancelled `1`, allowed `0`;
- tagged source allow exact `1`, tagged destination allow exact `1`;
- exact one item đến destination slot `0`;
- PUBLISHED-only publication tại source chest/local slot `4`, owner null, history delta `0` đúng actorless contract;
- restart tạo exact one completed destination observation;
- cleanup removed one item/three blocks, observations zero.

## Giới hạn

Chưa chứng minh hopper minecart, double-hopper concurrency, chunk unload giữa transfer, multi-server/writer, scale hoặc production. Không mở destructive action/issuance.
