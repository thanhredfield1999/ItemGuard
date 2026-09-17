# Controlled double-chest physical-source journey

Ngày: 2026-08-26

## Kết luận

`VERIFIED CONTROLLED PAPER` cho real double-chest click publication, exact physical-half source mapping, completed observation epoch, clean restart và cleanup trên Paper 1.21.11.

Không phải production verification và không mở release gate toàn dự án.

## Candidate và fixture

- Runtime candidate: `aa0361c08dc1dd41ffcb98214c1ba82cd90082259a311ee26324f360bb0a7980`.
- Corrected probe: `8c6c7f86fa8589d7c0880f3b78155a0ec81a638fccc8bd04d1579f5924bc3163`.
- Run token: `98b434b0-fecc-4ee6-8c3b-61ab583fbc06`.
- Player UUID: `ae1f9dd4-5884-3a6f-bf75-0ac44a4e4e03`.
- World UUID: `34486cdf-24f0-4ade-9f92-f820b9605e20`.

## Click-publication phase

Server khởi động với `performance.container-scan-enabled=false`. Probe reject nếu key này true.

Probe mở một double chest rỗng, sau đó mới đặt hai `DIAMOND_SWORD` chưa tag vào combined raw slots:

| Side API | Raw slot | Local slot | Physical block | Code | Item UUID |
|---|---:|---:|---|---|---|
| left | 3 | 3 | `9,-60,-2` | `01FVXT` | `a68867e1-80c2-45e6-ae8f-85c89cd7f872` |
| right | 30 | 3 | `10,-60,-2` | `HJP6FI` | `c221f532-44d8-4969-95d3-71beaad47d8a` |

Bot gửi một normal left-click packet cho raw slot3 và một cho raw slot30. Server-side probe `LOWEST` ghi đúng `leftClickCount=1`, `rightClickCount=1` khi item còn untagged. Production listener `HIGH` cancel click rồi publish.

DB click seal:

- tracked `+2`;
- snapshots `+2`;
- publications/PUBLISHED `+2/+2`;
- SPAWN history `+2`;
- exact source keys kết thúc bằng physical half coordinates và local slot3;
- publication/snapshot/physical SHA khớp;
- observations/findings/duplicates_detected/reclaim/triggers: zero delta.

Điều này tách click attribution khỏi scanner.

## Completed observation epoch

Khi server đã dừng sạch, clone đổi sang container scan true và interval100 rồi khởi động lại.

Atomic read transaction capture epoch `1787738657367` trước retention:

- exactly two rows;
- `epoch_complete=1`;
- `holder_type=CONTAINER`;
- holders:
  - `BLOCK:34486cdf-24f0-4ade-9f92-f820b9605e20:9:-60:-2`;
  - `BLOCK:34486cdf-24f0-4ade-9f92-f820b9605e20:10:-60:-2`;
- local slot `3` cho cả hai;
- exact distinct code/UUID tương ứng từng half;
- publication timestamps precede observations;
- zero findings trong epoch.

Hai identities khác nhau nên no-finding là expected; gate này kiểm physical source mapping, không kiểm duplicate detection cùng identity.

## Restart và cleanup

- Clean restart re-derived cùng left/right physical block mapping và exact code/UUID/SHA.
- Cleanup clear hai side slots trước khi xóa block.
- Removed exactly two items và two chest blocks.
- Observation retention về zero.
- DB integrity `ok`; durable canonical/snapshot/publication/history giữ nguyên.
- Clone restore: anti-dupe false, notify true, container scan true, interval600, reclaim issuance false.
- Ports/process controlled đã đóng.

## Evidence

- `E:\AI.WORK\itemguard-paper-smoke\double-chest-click-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\double-chest-observation-capture.json`
- `E:\AI.WORK\itemguard-paper-smoke\double-chest-final-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\double-chest-cleanup-evidence.json`
- `E:\AI.WORK\backups\double-chest-98b434b0-20260826-170845.tar.gz`
- Archive SHA-256: `dabfdaab737125127c1c621ca35c93e4e6adc546f1d0a817f989286a751fb1d0`.

## Post-runtime Java attribution

- Full clean Java 21: `62` suites / `217/217` PASS.
- Current rebuilt whole-JAR: `805360ad14978c5c03b4e206cadf175c1eebe9da2db922c707564778a7f0b40b`.
- Runtime whole-JAR: `aa0361c08dc1dd41ffcb98214c1ba82cd90082259a311ee26324f360bb0a7980`.
- Entry comparison: `406/406` non-manifest entries, zero missing/changed byte entries; không đồng nhất hai whole-JAR hashes.

## Scope không được claim

- same exact identity duplicated trong double chest;
- hopper transfer;
- chunk unload mid-scan;
- multi-writer/multi-server;
- production scale hoặc production deployment.
