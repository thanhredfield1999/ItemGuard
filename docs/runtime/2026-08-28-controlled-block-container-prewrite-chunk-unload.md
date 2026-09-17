# Controlled Paper — block-container pre-write chunk unload

Ngày: 2026-08-28

## Kết luận

`VERIFIED` cho block-container source chunk unload trong in-flight first-publication trước physical write:

- ItemGuard SHA-256: `2e1fb7d8717a02b316bef9c017d09d67b8705c694519433276bd6e316d5adc5e`;
- probe SHA-256: `59cbd042ed5d0bd25a1912c5393259022ad34007461912fc58b8968d49676f3d`;
- Paper: `1.21.11-131-ver/1.21.11@6d5b910`;
- token: `02502468-4456-4f73-acfb-8d2b37123488`;
- authoritative attempt: `attempt-2`;
- verdict: `PASS_AUTHORITATIVE_CONTAINER_PREWRITE_CHUNK_UNLOAD`.

Overall ItemGuard vẫn `NOT RELEASE READY`. Production không được truy cập/deploy/restart.

## Source defect và correction

`OBSERVED`: block publication trước đây capture inventory/handle/digest rồi dùng lại captured inventory sau async reserve. Paper cảnh báo tile inventory có thể không còn valid khi block state thay đổi. Source cũ không re-resolve loaded world/block/local slot trước write.

Correction:

1. loaded-only physical resolver từ `Location + localSlot`;
2. không force-load chunk;
3. `getState(false)` + `Chest.getBlockInventory()` cho exact physical half;
4. re-resolve live slot trong cả `matches()` và `write()`;
5. recheck physical handle + digest, write vào current live inventory;
6. generic inventory helper thu hẹp thành private player-only seam.

Regression:

- ba RED contracts đúng root cause/bypass;
- focused `28/28` PASS;
- full Java 21 `292/292` PASS;
- probe build/static contract PASS;
- `git diff --check` PASS.

## Independent review

Exact Claude Opus 4.8:

- verdict `PASS_FOR_CONTROLLED_PAPER`;
- blockers `[]`;
- prompt SHA-256 `29cbbddcd2ea98544d9200a3be4185eb59d9eb728d8c4a156fc59108cce43c3e`.

Reviewer yêu cầu stopped DB row proof, unload/completion ordering, `journal_mode=DELETE`, exact trigger filter, offline NBT, real load event và main-thread Bukkit access. Authoritative attempt đóng đủ các check này.

## Authoritative matrix

### Prepare: unload trước physical write

Physical source:

`BLOCK_CONTAINER_SLOT:34486cdf-24f0-4ade-9f92-f820b9605e20:312:-57:-8:3`

- real untagged hopper attempt/cancel/allow: `1/1/0`;
- unload request: `1787875340307`;
- real `ChunkUnloadEvent`: `1787875340380`;
- delayed publication row created: `1787875340082`;
- publication abort completion: `1787875356253`.

Ordering:

`created_at < unloadRequestedAt <= unloadedAt <= updated_at`.

First proposal:

- code `3VZ5IG`;
- publication `bce24876-20b9-424f-8b52-21036f1e36f5`;
- state/detail `ABORTED/SOURCE_CHANGED`;
- source digest bằng exact pre-unload serialized source hash `23e2ec47…48ad`.

Stopped DB delta:

- publications `+1`;
- ABORTED `+1`;
- PUBLISHED/tracked/snapshots `+0/+0/+0`;
- PREPARED `0`;
- history/observations/findings/stats/claims zero delta;
- exact controlled trigger được read-back rồi drop; trigger count `0` trước retry;
- integrity `ok`, `journal_mode=delete`.

### Offline NBT

Sau clean stop, CPython 3.11.15 NBT parser đọc region `world/region/r.0.-1.mca`:

- target block `minecraft:chest`;
- hopper block `minecraft:hopper`;
- chest có đúng một entry tại slot `3`;
- item `minecraft:diamond_sword`;
- probe run-token hit `1`;
- ItemGuard code/UUID key hit `0`;
- hopper entries `0`.

Region SHA-256: `8c69c53a8fa3810bc4e16fefa222ea39fc19570d4c049df6d6691729445ebb3e`.

### Retry và restart

Retry bắt đầu khi chunk unloaded; real `ChunkLoadEvent` được quan sát. Exact original untagged hash vẫn `23e2ec47…48ad`.

- retry untagged attempt/cancel/allow: `1/1/0`;
- tagged allow/cancel: `1/0`;
- new code/UUID: `NABXUS / fe724d5f-688d-492e-a56d-a9c23e4af577`;
- same physical source key;
- distinct code/UUID so với aborted proposal;
- publication mới `PUBLISHED`;
- canonical/snapshot/PUBLISHED `+1/+1/+1`;
- tagged SHA `190da996…b6bf`.

Clean restart giữ exact code/UUID/SHA. Cleanup xóa đúng `1` item và `2` blocks; DB logical state không đổi qua restart/cleanup.

## Independent verification

Verifier độc lập đọc preserved evidence, không dùng runtime self-assertion làm kết luận duy nhất:

- `56/56` checks PASS;
- exact candidate/probe continuity;
- attempt 1 non-authoritative classification;
- event/unload/order/DB/NBT/retry/restart/cleanup checks;
- four clean stops có DB close + all dimensions saved;
- backup/read-back/restore/ports/DB integrity/sidecar checks.

Receipt: `attempt-2/independent-verification.json`.

## Audit chain

- `attempt-1`: runtime prepare và stopped DB abort PASS; offline NBT toolchain fail vì alias `python` là CPython 3.14 nhưng parser bundle chứa native NumPy CPython 3.11. Classification `NON_AUTHORITATIVE_HARNESS_TOOLCHAIN_FAILURE`; restore PASS.
- `attempt-2`: chỉ sửa harness interpreter/attempt namespace; exact candidate/probe/review bất biến. Cả 4 phases + independent verifier PASS.

Không relabel attempt 1 thành production failure.

## Restore

Operational backup:

- `E:/AI.WORK/backups/itemguard-container-chunk-pre-runtime-20260828-064722.tar.gz`;
- SHA-256 `73798dca8f56ddfa260a6bc0142e1e0100870468ee9056a9387a37ab1c1329ca`;
- `191` files read-back verified.

Sau run:

- baseline ItemGuard/probe/DB byte hashes restored;
- DB logical SHA restored `944413c7d472f2a3af42ef42e998449ba18c0dc080529295de5dd9b13682f3e0`;
- integrity `ok`, trigger `0`;
- lock/WAL/SHM/journal absent;
- ports `57485/36104/57486` closed.

## Residual scope

Vẫn `NOT VERIFIED` hoặc unsupported:

- double-chest chunk-border unload và block replacement timing khác;
- hơn hai hoppers/density/performance;
- non-chest/custom inventories, throwing accessors, unopened scanning;
- Folia/regionized threading;
- multi-server/multi-writer/shared filesystem;
- broader crash/unload stress, relocation/tombstone;
- production deployment/verification.

Evidence root: `E:/AI.WORK/evidence/itemguard-container-chunk-unload-20260828/`.

External sealed archive receipt (tạo sau archive, không tự nhúng vào archive):

- archive: `E:/AI.WORK/evidence/itemguard-container-chunk-unload-20260828.tar.gz`;
- SHA-256: `d8db1d539d503441a15c3001010275c9dd0c67c36aed1725dc13d37e7d211b87`;
- `61` members (`60` payload files + internal checksum manifest), read-back verified.
