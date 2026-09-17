# First-tag async durable-first — controlled Paper

## Phạm vi

- ItemGuard artifact: SHA-256 `f0ef15d0f2c765a3a84dbe9428673ff96cb79af12e4f0d122eff47f6cac4a712`, 12,249,060 bytes.
- Probe artifact: SHA-256 `aac5bc1f4a03dc962586d07992ee9da41194cd3d3dc72ddecfb7b1ff1a167707`, 10,142 bytes.
- Paper `1.21.11`, Java 21, isolated workspace `E:\AI.WORK\itemguard-paper-smoke`.
- BotChecker holder run `0d6ef437-44f8-4797-985f-b9a950d62dd2` negotiated protocol 774 và giữ một chunk được player load tự nhiên trong 110 giây. Probe không force-load chunk.
- `/igbench` đo riêng thời gian primary-thread `World.dropItem()` (`dispatchTotalMs`) và thời gian tới khi mọi fixture có đủ PDC (`readyPdcMs`). Sau đó fixture entity được cleanup; canonical DB rows được giữ làm evidence.

## Kết quả hậu sửa

| Count | Complete | Dispatch total ms | Avg dispatch ms | p50 ms | p95 ms | p99 ms | Max ms | PDC ready ms |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1/1 | 1.759 | 1.759 | 1.709 | 1.709 | 1.709 | 1.709 | 85.141 |
| 10 | 10/10 | 1.557 | 0.156 | 0.110 | 0.518 | 0.518 | 0.518 | 197.882 |
| 50 | 50/50 | 6.901 | 0.138 | 0.106 | 0.471 | 0.808 | 0.808 | 692.620 |
| 100 | 100/100 | 10.081 | 0.101 | 0.092 | 0.140 | 0.353 | 0.401 | 1437.969 |

Raw JSONL: `E:\AI.WORK\itemguard-paper-smoke\plugins\ItemGuardSmokeProbe\first-tag-benchmark.jsonl`.

## So sánh baseline

- Baseline artifact `b84fa244…3f5d`: burst 100 chặn primary thread `1245.100 ms`.
- Hậu sửa artifact `f0ef15d0…a712`: burst 100 dispatch `10.081 ms`, giảm khoảng `123.5x`; canonical-ready/PDC hoàn tất bất đồng bộ sau `1437.969 ms`.
- Không suy diễn production TPS/throughput từ fixture cô lập này.

## DB và journey postcondition

- Schema runtime: v6.
- Sau final benchmark: `PUBLISHED=333`, `PREPARED=0`, `tracked_items=500`, `item_snapshots=500`.
- Final inventory journey `4c63613c-98b9-4f9d-8a00-bde072d646b9`: PASS; exact `PLAYER_SLOT` publication tạo code `SQRO58`, snapshot v1 payload 191 bytes.
- Final exact-present SOS `74e68e4b-91e6-4cee-8dd1-9d2a47a2cd51`: PASS; persisted claim `DENIED`, detail `PLAYER_INVENTORY`; không issuance.
- DB cuối evidence: `PUBLISHED=334`, `PREPARED=0`, `tracked_items=501`, `item_snapshots=501`.

## Kết luận

- `VERIFIED controlled`: blocker synchronous first-tag trên primary thread đã được xử lý cho entity workload benchmark.
- `VERIFIED controlled`: entity và player-inventory publication đều physical-writeback + canonical snapshot thành công trên artifact final.
- `NOT VERIFIED`: production workload, graceful Paper process exit, crash injection đúng cửa sổ giữa physical PDC và canonical publish, WorldGuard runtime và external adapters.
- Release vẫn `NOT RELEASE READY` vì các gate integration/issuance còn mở.
