# Double-chest physical-source attribution defect

Ngày: 2026-08-26

## Trạng thái

`VERIFIED CONTROLLED PAPER` cho phạm vi double chest trên Paper 1.21.11. Chưa deploy hoặc verify production.

## Triệu chứng tái hiện

`OBSERVED` trong source trước fix:

- `scanContainerInventory` và `scanOpenBlockContainerInventory` chỉ chấp nhận `Inventory#getHolder() instanceof Container`.
- Paper 1.21.11 trả `DoubleChest` làm holder cho `DoubleChestInventory`, vì vậy double chest bị reject.
- `scanContainer(Block)` có thể nhận combined inventory 54 slots từ một chest half nhưng tạo source key bằng tọa độ block đầu vào và raw slot cho toàn bộ inventory. Slots thuộc half còn lại bị gắn sai physical block/local slot.
- `ItemListener` dùng cùng single-container assumption nên first-publication qua click double chest không có physical source hợp lệ.

## Root cause

Code đồng nhất “inventory container” với một `Container` block và một slot namespace. Double chest là inventory tổng hợp gồm hai physical `Container` blocks và hai local slot namespaces. Midpoint location, một block coordinate hoặc combined raw slot không phải physical source bền vững.

## Fix tối thiểu

- `DoubleChestSlotPolicy` ánh xạ combined raw slot sang side + local slot.
- `BlockContainerPhysicalSlotResolver` trả exact side inventory, physical block location và local slot; invalid holder/location/slot fail-closed.
- Dùng resolver chung cho:
  - click first-publication;
  - `scanContainer(Block)`;
  - `scanContainerInventory`;
  - scheduled open-container observation.
- Async publication mutate exact side inventory/local slot và dùng source key `BLOCK_CONTAINER_SLOT:<world>:<physical-half>:<local-slot>`.

## Regression tests

- `DoubleChestSlotPolicyTest`: raw `0/26/27/53`, invalid sizes/ranges.
- `DoubleChestPhysicalSourceWiringContractTest`: resolver physical side/location, bốn call paths và exact config gate key.
- Focused double-chest policy/wiring: `5/5` PASS.
- Full clean Java 21: `62` suites / `217/217`, zero failures/errors.

## Controlled runtime

Run token `98b434b0-fecc-4ee6-8c3b-61ab583fbc06`:

- click phase scanner-disabled at plugin enable;
- real double chest, raw slots `3/30`, local slot `3` per half;
- exact server LOWEST click evidence `1/1` while items untagged;
- exact `+2` canonical/snapshot/PUBLISHED/SPAWN, zero observation/finding/stat delta during click phase;
- separate scanner-enabled startup captured one completed epoch with exactly two `CONTAINER` rows at two physical block holders/local slot3 and no finding;
- clean restart preserved side assignment/code/UUID/SHA;
- cleanup removed exactly two items and two blocks; observations zero; DB integrity `ok`.

## Review history

Initial exact Opus 4.8 pre-runtime review: `BLOCK` vì scanner có thể publish trước click và thiếu server-side click evidence. Fixture được harden; correction review: `PASS`. Final exact Opus 4.8 review: `PASS`, required fixes none.

## Giới hạn còn lại

Không chứng minh same exact identity trong double chest, hopper transfer, chunk unload giữa scan, multi-writer/server, scale hoặc production.

Production: `NOT DEPLOYED / NOT VERIFIED`.
