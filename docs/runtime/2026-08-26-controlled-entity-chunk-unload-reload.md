# Controlled entity chunk-unload/reload

Ngày: 2026-08-26

## Kết luận

`VERIFIED controlled Paper` cho một ground-item entity non-stackable ở remote chunk không có player/force/plugin holder.

Exact entity được unload trong exact delayed reconciliation transaction sau durable receipt, rồi persist/reload với cùng entity UUID, ItemGuard code/item UUID và serialized SHA. Same publication commit `PUBLISHED`; canonical/snapshot exact digest. Offline entity-region NBT và restart đều xác nhận đúng một exact entity.

Production source không đổi. Không cần production change. Project vẫn `NOT RELEASE READY`.

## Exact oracle

- Candidate: `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`.
- Run token: `36201cb7-15de-4f21-9c31-57f5d1cceb8d`.
- World/chunk: `34486cdf-24f0-4ade-9f92-f820b9605e20`, `(19,0)`.
- Entity UUID: `b2b0f8aa-95e9-437b-bfeb-39bd51ed288b`.
- Identity: `U6I2ZW / 6fe5fd27-3733-4cb5-b925-db144f1c12b9`.
- Publication: `32661d99-b375-47dd-9345-7e995ccafe16`.
- Serialized payload: `236` bytes.
- Serialized SHA: `6d09d0d2ff011a7944c2c3eeee22a72d3c1cdc94c919d8c7388e5ae0984fecd3`.

## Journey

1. Offline exact hold-first trigger giữ publication ở `PREPARED`; physical entity đã có exact PDC, canonical/snapshot `0/0`.
2. Graceful stop; phase-1 seal xác nhận DB integrity `ok`, one PREPARED, exact source/digest.
3. Offline thay bằng exact `AFTER UPDATE PREPARED→PUBLISHED` trigger chỉ match publication ID trên. Recursive 30M giữ transaction trước statement return/commit.
4. Restart với fresh readiness cache. Watcher arm trước `loadChunk`; `EntitiesLoadEvent` chứa exact UUID. Production entity-load path mint exact receipt.
5. Sau rollback journal ổn định 250 ms, fixture revalidate exact world/chunk/UUID/PDC/SHA và no holders, rồi gọi `unloadChunkRequest` trên main thread.
6. Request accepted; hai ticks sau `EntitiesUnloadEvent` chứa exact UUID, chunk unloaded, UUID unresolvable và journal vẫn active.
7. Offline seal sau DB close/dimensions save: same publication `PUBLISHED`, canonical/snapshot exact SHA, zero post-unload observations.
8. Full offline entity-region parser: 30 entity chunks, đúng một entity UUID, một code hit và một item UUID hit trong exact `minecraft:item`/diamond sword.
9. Trigger được gỡ offline. Restart reload exact chunk: `EntitiesLoadEvent` chứa exact UUID, same UUID/PDC/SHA, readiness true. Graceful stop và NBT verifier lặp lại PASS.

## RCA fixture bị loại

Run `04856304-...` không được dùng làm PASS. `World.unloadChunk(..., true)` trả `false`; entity vẫn tồn tại đúng UUID/PDC trong NBT. Diagnostic riêng xác nhận Paper path phù hợp là `unloadChunkRequest`: accepted, event exact, chunk unloaded và UUID absent sau 2 ticks. Run mới dùng token `36201cb7-...`.

## Verification

- Focused Java 21: `19/19` PASS.
- Full Java 21: `58` suites, `201/201` PASS, failures/errors/skips `0/0/0`, `BUILD SUCCESS`.
- Clean JAR `841f1511…b13c` và runtime candidate có `400/400` non-manifest entries byte-identical; target đã khôi phục candidate exact.
- `git diff --check` PASS.
- Final DB: schema 7, integrity `ok`, tracked/snapshot/publication `515/515/357`, PREPARED/trigger/active claim `0/0/0`.

## Independent review

Exact `cc/claude-opus-4-8` correction review:

- `CORRECTION: ACCEPTED`;
- `VERDICT: PASS`;
- `PRODUCTION CHANGE REQUIRED: NO`.

Reviewer xác nhận exact trigger tồn tại trong phase 2 rồi mới được gỡ; one serial SQLite owner + exact-only long trigger + stable journal + exact revalidation không có alternative transaction cụ thể phù hợp toàn evidence.

Review: `docs/reviews/2026-08-26-entity-chunk-unload-opus-review.md`.

## Scope limits

Không chứng minh entity destruction, merge/stackable, concurrent unload, crash/force-kill, scale/backpressure, atomic Bukkit+SQLite, production hoặc release readiness.

## Evidence

- `E:\AI.WORK\itemguard-paper-smoke\entity-chunk-unload-final-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-chunk-unload-nbt-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-chunk-unload-phase2-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-chunk-unload-recover-orchestrator.log`
- `E:\AI.WORK\itemguard-paper-smoke\entity-chunk-unload-restart-orchestrator.log`
- Archive: `E:\AI.WORK\backups\itemguard-entity-chunk-unload-36201cb7-20260826-031543.tar.gz`, SHA-256 `88021c838652597b047e1bfc7bbc8c064233d4ebdd387da55605a205a183c4be`; manifest SHA-256 `6dbae63784a1645b1b1fa22cfc07f3d03bf4b0e4954432a797d302eca078c201`.
