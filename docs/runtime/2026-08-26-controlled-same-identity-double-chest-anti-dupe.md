# Controlled same-identity double-chest anti-dupe journey

Ngày: 2026-08-26

## Kết luận

`VERIFIED CONTROLLED PAPER` cho một exact non-stackable identity xuất hiện đồng thời tại hai physical halves của một real double chest trên Paper 1.21.11.

Không phải production verification và không mở destructive/reclaim/release gate.

## Candidate và fixture

- Runtime candidate: `805360ad14978c5c03b4e206cadf175c1eebe9da2db922c707564778a7f0b40b`.
- Probe: `fff13d8421d65485821ef06b357b1c2cf6d66420b2b058a505dc1787cba97fb4`.
- Run token: `97353438-bfd4-43d3-bbe3-daf894757d39`.
- Exact identity: `MK2BR2 / 2d39fd99-6f35-4b01-b6c5-d755b11ad8ef`.
- Serialized SHA-256: `57d8a29e94e02b1d1a0253ced8c522154c10f329f2d9686c39a4b0323cc2a293`.

## Publication phase

Server khởi động với anti-dupe false, container scan true, interval600.

Probe đặt một fresh `DIAMOND_SWORD` tại left side local slot3 trước `player.openInventory(doubleChest)`. Real `InventoryOpenEvent` đi qua production:

`ContainerListener → scanContainerInventory → BlockContainerPhysicalSlotResolver → requestInventorySlotTag`.

Sau production `PUBLISHED/readiness`, probe clone exact serialized bytes sang right side local slot3; không gọi scanner/finalizer hoặc direct publication API.

Exact seal:

- canonical/snapshot/publication/PUBLISHED/SPAWN `+1/+1/+1/+1/+1`;
- source key chỉ có left physical block `-7,-60,8:3`;
- right physical source `-6,-60,8:3` có zero publication;
- publication owner/history player UUID đúng controlled player;
- publication/snapshot/physical SHA khớp;
- observations/findings/stats/reclaim/trigger zero delta.

## Detection phase

Server dừng sạch rồi clone-only đổi anti-dupe true, action `NOTIFY`, interval100, cooldown600000.

Atomic watcher bắt finding trước retention:

- finding ID `4`;
- scan epoch `1787741743307`;
- `CONFIRMED/NOTIFY`;
- `distinct_locations=2`, detail exact;
- đúng hai completed observations cho cùng code/item UUID;
- holder type `CONTAINER`;
- physical holders:
  - `BLOCK:34486cdf-24f0-4ade-9f92-f820b9605e20:-7:-60:8`;
  - `BLOCK:34486cdf-24f0-4ade-9f92-f820b9605e20:-6:-60:8`;
- local slot `3` cả hai;
- finding/stat `+1/+1`.

Production scheduler/finalizer tự tạo epoch/finding; probe không gọi internals.

## Restart, cooldown và cleanup

Clean restart tạo completed epoch mới `1787741854276` với cùng exact two physical observations. Finding ID4 toàn row giữ byte-identical và stat không tăng—positive cooldown proof, không phải absence-of-detection inference.

Cleanup:

- clear exact two side slots trước khi remove blocks;
- removed exactly two items và two blocks;
- observations về zero sau empty epoch;
- durable canonical/snapshot/publication/SPAWN/finding giữ nguyên;
- DB integrity `ok`, active claims/triggers zero.

Clone cuối:

- anti-dupe false;
- cooldown5000;
- container scan true;
- interval600;
- issuance false;
- ports/process controlled đóng.

## Build attribution

- Full Java 21: `62` suites / `217/217` PASS.
- Runtime whole-JAR: `805360ad14978c5c03b4e206cadf175c1eebe9da2db922c707564778a7f0b40b`.
- Rebuilt whole-JAR: `269ea9b9dd592c81e5119085da91fb88763371d8d0e8d927f1cbe28dfbf863cb`.
- `406/406` non-manifest entries byte-identical; zero missing/changed entries. Không đồng nhất hai whole-JAR hashes.

## Independent review và archive

- Final approved Opus 4.6 fallback: `PASS`, required fixes none, production change `NO`.
- Exact Opus 4.8 attempts thiếu usable required schema; không được tính là verdict.
- Archive: `E:\AI.WORK\backups\double-chest-duplicate-97353438-20260826-180034.tar.gz`.
- Archive SHA-256: `c912c88d272beede699030251dff5b359cfbf7c127a2ab693b996038472cfd4f`.

## Harness exclusions

- Pre-start token `f7317214-...`: broad cleanup glob xóa baseline trước khi Java/Paper launch; no runtime/world/DB mutation; `HARNESS_ONLY`.
- First correction-review response thiếu output schema; helper exit AssertionError, không phải reviewer verdict. Correction review hợp lệ sau đó dùng approved Opus 4.6 fallback.

## Scope không được claim

- hopper transfer;
- unopened `scanContainer(Block)` duplicate journey;
- chunk unload mid-scan;
- multi-writer/multi-server;
- production scale/destructive action/production deployment.

Project vẫn `NOT RELEASE READY`; production `NOT DEPLOYED / NOT VERIFIED`.
