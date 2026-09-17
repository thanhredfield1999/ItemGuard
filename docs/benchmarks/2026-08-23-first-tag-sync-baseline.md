# Baseline first-tag synchronous — controlled Paper

## Phạm vi

- Artifact ItemGuard: SHA-256 `b84fa2446179327f8bf18bd8fe026bda74a102b55a16e3c35e50f2e90aae3f5d`.
- Paper `1.21.11`, Java 21, isolated workspace `E:\AI.WORK\itemguard-paper-smoke`.
- Probe SHA-256 `d9617b93bb926a25b0a04dba83e1a203b1e8a0b32bfe37e55a68b16a50e6c06e`.
- `/igbench` chạy trên Paper primary thread, gọi `World.dropItem()` với `DIAMOND_SWORD`; `ItemSpawnEvent` chạy first-tag identity+snapshot SQLite transaction. Probe đo thời gian từng call và tổng burst, kiểm đủ `itemguard:code` + `itemguard:item_uuid`, rồi xóa entity fixture.
- DB trước benchmark: 5 tracked rows, 5 snapshots.

## Kết quả

| Lượt | Complete | Total ms | Avg ms | p50 ms | p95 ms | p99 ms | Max ms |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 warmup | 1/1 | 461.388 | 461.388 | 461.372 | 461.372 | 461.372 | 461.372 |
| 1 | 1/1 | 15.439 | 15.439 | 15.438 | 15.438 | 15.438 | 15.438 |
| 10 | 10/10 | 138.161 | 13.816 | 13.516 | 16.475 | 16.475 | 16.475 |
| 50 | 50/50 | 671.864 | 13.437 | 13.206 | 15.477 | 16.516 | 16.516 |
| 100 | 100/100 | 1245.100 | 12.451 | 12.387 | 13.812 | 15.157 | 15.563 |

DB sau benchmark: 167 tracked rows, 167 snapshots, 0 orphan snapshots. Delta 162 khớp `1+1+10+50+100`.

## Kết luận evidence

- `OBSERVED`: first-tag hiện chặn primary thread khoảng 12–16 ms/item sau warmup.
- `OBSERVED`: burst 10 chặn 138 ms (~2.8 tick), burst 50 chặn 672 ms (~13.4 tick), burst 100 chặn 1.245 giây (~24.9 tick), chưa kể workload server khác.
- `VERIFIED controlled`: blocker synchronous-main-thread là thực, không chỉ suy luận code review.
- Không suy diễn production throughput từ workload cô lập này.

Raw JSONL: `E:\AI.WORK\itemguard-paper-smoke\plugins\ItemGuardSmokeProbe\first-tag-benchmark.jsonl`.
Log markers: `E:\AI.WORK\itemguard-paper-smoke\logs\latest.log`.

## Gate

`NOT RELEASE READY`. Cần async durable-first với source revalidation và benchmark post-fix tương đương.
