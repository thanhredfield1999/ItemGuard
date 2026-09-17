# Controlled real hopper transfer publication

Ngày: 2026-08-26

## Kết luận

`VERIFIED CONTROLLED PAPER` cho real chest → hopper → chest transfer của một fresh eligible non-stackable item.

Production vẫn `NOT DEPLOYED / NOT VERIFIED`; project vẫn `NOT RELEASE READY`.

## Artifact

- Paper: `1.21.11-131`, Java `21`.
- Production candidate runtime SHA-256: `1ef91c0c7f15cb9b74ef2a46f875bab322a4d415ac398c8146c465c1b7f83509`.
- Corrected probe SHA-256: `d5eb8a81d0e1f7d5282d76a832749cf7943e4ada3b60caefc5d9e9798d165cb1`.
- Rebuilt whole-JAR SHA-256: `258231e0dffe9d1a6048254fa9efa84ca1b9ef7ee6bae4ce1d66b6da8158b641`.
- Runtime/rebuild: `408/408` non-manifest entries byte-identical, zero differences.
- Authoritative run token: `c1331bad-149f-4da8-9f36-55fdd9c18986`.

## Fixture

Physical layout:

- source single chest: `(2,-59,7)`, fixture local slot `4`;
- hopper: `(2,-60,7)`, facing EAST;
- destination single chest: `(3,-60,7)`;
- final destination local slot: `0`.

Probe tạo fresh `DIAMOND_SWORD`, xác nhận chưa có `code/item_uuid`, rồi chỉ quan sát real `InventoryMoveItemEvent` tại LOWEST/MONITOR và physical inventories. Probe không gọi tracking/publication/scanner/finalizer internals.

## Exact event evidence

Identity cuối:

- code `SAKB9A`;
- item UUID `f6cb2eb9-abcf-4a96-b5f6-ef15972d6c41`;
- serialized SHA-256 `9f9e88a38c61f5520812acf1cd1c63ad3c08af80fbd9d5d5755ed8a0ce4d0aad`.

Telemetry exact:

- untagged source attempts: `1`;
- cancelled untagged source attempts: `1`;
- allowed untagged source attempts: `0`;
- allowed untagged destination attempts: `0`;
- allowed tagged source attempts: exactly `1`;
- allowed tagged destination attempts: exactly `1`;
- cancelled tagged source attempts: `0`;
- source/hopper empty và destination có đúng một physical item.

## Publication evidence

Baseline → prepare delta:

- canonical tracked `+1`;
- snapshot `+1`;
- publication `+1`;
- `PUBLISHED +1`;
- `PREPARED/ABORTED/history/observations/findings/stats/claims/triggers`: zero delta.

Production proof là actual `tag_publications` PUBLISHED row, không phải SPAWN history:

- source key `BLOCK_CONTAINER_SLOT:34486cdf-24f0-4ade-9f92-f820b9605e20:2:-59:7:4`;
- owner UUID `null`;
- exact code/UUID/SHA khớp destination marker và snapshot;
- exact identity history rỗng, đúng `markPublished()` actorless contract.

## Restart/reconciliation

Clean restart với scan interval `100` tạo completed epoch `1787748430177`:

- exactly one `CONTAINER` observation;
- holder `BLOCK:34486cdf-24f0-4ade-9f92-f820b9605e20:3:-60:7`;
- slot `0`;
- source chest/hopper không xuất hiện;
- zero finding/stat delta.

## Cleanup

- removed items: `1`;
- removed blocks: `3`;
- observations: `0`;
- durable canonical/snapshot/PUBLISHED row giữ nguyên;
- DB integrity `ok`;
- ports `57485/36104` đóng và session lock free.

Clone đã restore:

- `anti-dupe.enabled=false`;
- `detection-cooldown-ms=5000`;
- `container-scan-enabled=true`;
- `inventory-scan-interval=600`;
- `issuance-enabled=false`.

## Audit chain

- initial exact Opus 4.8: `BLOCK` fixture evidence;
- correction exact Opus 4.8: `PASS`;
- historical run `c684074c-...`: final `BLOCK`, không authoritative;
- final correction exact Opus 4.8: `PASS`, rerun authorized;
- fresh run `c1331bad-...`: authoritative final exact Opus 4.8 `PASS`, required fixes none.

Historical harness-only evidence:

- probe startup thiếu `itemguard.controlledRoot`: DB baseline unchanged, command không chạy;
- first DB sealer giả sai history `+1` và cột `bytes`: corrected against source/schema trên stopped immutable result;
- historical run dùng uncapped `>=1` counters nên không được promote.

## Giới hạn

Không suy rộng sang hopper minecart, double-hopper concurrency, chunk unload mid-transfer, multi-server, scale hoặc production. Single-chest slot4 dựa structural contract + runtime DB source-key; chưa có standalone MockBukkit resolver test.

## Archive

- `E:\AI.WORK\backups\hopper-transfer-c1331bad-20260826-195527.tar.gz`
- SHA-256: `3ba92dbdbddfa9c0999277c3c8bed51ae13e2cfc12d0d9c9ee38a25c0cf87b18`
- Archive integrity: PASS.
