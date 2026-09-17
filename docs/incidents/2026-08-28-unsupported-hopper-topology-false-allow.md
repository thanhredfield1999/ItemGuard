# Unsupported hopper topology false-allow

Ngày: 2026-08-28

## Trạng thái

`CORRECTED + CONTROLLED PAPER VERIFIED`. Không có bằng chứng defect này từng chạy production; production không được truy cập/deploy/restart trong task.

## Symptom

Source hiện hữu cho phép tagged-ready item qua generic `InventoryMoveItemEvent` mà không kiểm tra source/destination có ánh xạ được tới physical block-container topology hay không. `HopperMinecart`, `StorageMinecart` và virtual inventories không phải Bukkit block `Container`, nên publication/reconciliation scanner trả zero nhưng transfer vẫn có thể được `ALLOW`.

Tác động: tracked item có thể rời tập physical container sources mà ItemGuard hiện hỗ trợ quan sát. Đây là fail-open topology boundary, không phải bằng chứng duplicate/corruption đã xảy ra.

## Root cause

`HopperTransferPolicy` trước correction chỉ nhận identity readiness/eligibility. `ContainerListener` gọi policy trước khi có contract về physical topology support. Publication resolver đã fail-closed bằng cách không resolve entity/virtual holders, nhưng transfer policy không dùng thông tin đó.

## Correction

- thêm `BlockContainerPhysicalSlotResolver.supports(Inventory)` dùng cùng resolver vật lý;
- truyền source/destination support vào policy;
- tracked/eligible item bị cancel nếu một đầu unsupported;
- source publication retry chỉ chạy khi cả hai đầu là supported block containers;
- double chest chỉ supported khi cả hai half có physical block location.

## Regression evidence

- policy RED: unsupported source/destination phải cancel tracked/eligible item, nhưng không block ineligible item;
- wiring RED: topology check phải xảy ra trước allow/cooldown/source scan;
- focused topology `21/21`, full Java 21 `288/288` PASS;
- Opus 4.8 exact review `PASS_FOR_CONTROLLED_PAPER`, blockers rỗng.

## Controlled Paper evidence

Candidate `0fa1e247c405cb5022e4cfedcbcc037ba61134bf4af008579460e0f63a592327`, Paper `1.21.11-131`:

- real double-chest→block-hopper→double-chest positive publication/transfer PASS;
- real block→HopperMinecart và HopperMinecart→block: fresh eligible và tagged item đều `1/1` cancel, item giữ nguồn;
- real StorageMinecart holder không phải block Container và bị cancel;
- virtual null-holder/location eligible và partial identity bị cancel; ineligible control ALLOW;
- exact identity/SHA giữ qua restart;
- negative phases zero DB/history/observation delta;
- cleanup và operational allowlist restore PASS.

Chi tiết: `docs/runtime/2026-08-28-controlled-hopper-topology-fail-closed.md`.

## Residual risk

Không hỗ trợ entity-container persistence. Double-hopper concurrency, chunk-unload mid-transfer, unopened scanning, throwing custom inventory accessors, hopper-density performance và production chưa verified.
