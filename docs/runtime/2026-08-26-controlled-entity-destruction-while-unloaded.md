# Controlled entity destruction while unloaded

Ngày: 2026-08-26

## Kết luận

`VERIFIED controlled Paper` cho semantics hẹp: một ground-item entity non-stackable đã durable `PUBLISHED`, được unload khỏi remote chunk, sau đó bị xóa external khi server offline bằng exact entity-region mutation; restart không hồi sinh hoặc nhân bản entity, còn canonical publication/snapshot ledger giữ nguyên.

Production source/JAR/server không đổi. Không cần production change. Project vẫn `NOT RELEASE READY`.

## Exact fixture

- Candidate: `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`.
- Run token: `a440fc96-9ddd-4579-82f1-9dbb4dfa40b7`.
- World/chunk: `34486cdf-24f0-4ade-9f92-f820b9605e20`, `(19,0)`.
- Entity UUID: `816684e4-c69b-40a7-b924-3abe2012f61b`.
- Identity: `GZNELR / e24627fd-ab33-49a6-82ae-c87cb4f2b1d9`.
- Publication: `62ab4680-ddf5-4427-9729-b349fb8ff746`.
- Serialized payload: `239` bytes, SHA-256 `7ea6b91f220162e253e075dae8166262a05b29de7c1f7516d646b9b90391c33f`.
- Prepare/unload probe: `2b9f7bcb18f695e05a1074f472553f25a679942c9cb6a70a60970f037031aef3`.
- Valid restart probe: `ab599b303715ba62d07ea11285694e42230c4636600b4fad75397d6830566dd4`; chỉ khác clone-only JSON whitespace parser.

## Journey

1. Fixture tạo exact diamond-sword entity ở remote chunk không có player/force/plugin holder.
2. Production first-publication hoàn tất; `isEntityIdentityReady=true` chỉ sau commit. Phase-1 offline seal xác nhận same source publication `PUBLISHED`, detail null; canonical/snapshot cùng 239 bytes/SHA; integrity `ok`, issuance false.
3. Main thread revalidate exact locator/PDC/SHA/readiness, `world.save()`, rồi `unloadChunkRequest`.
4. Paper accepted request; exact `EntitiesUnloadEvent` chứa UUID; chunk unloaded, `getEntity(UUID)=null`. Graceful stop có `Database connection closed` và `All dimensions are saved`.
5. Server offline: parser quét all standard-dimension `entities/*.mca`, tìm đúng một UUID/code/item UUID trong cùng exact entity. Backup target region byte-identical.
6. Mutator xóa đúng một entity-list entry trong original allocated sectors; compressed payload fit. Location/timestamp headers, file length, outside sectors, non-target region hashes và multiset SNBT fingerprint mọi non-target entity giữ nguyên. Target chunk count `3→2`; exact UUID/code/item UUID `1→0`; DB hash không đổi trong mutation.
7. Restart hợp lệ: exact chunk bắt đầu unloaded; `EntitiesLoadEvent` có xảy ra nhưng không chứa UUID; `getEntity` null, PDC absent, runtime target count đúng `2` theo receipt.
8. Graceful stop rồi semantic reseal: target chunk bằng exact backup trước mutation trừ đúng một target entity; all-world exact hits vẫn `0/0/0` qua 31 entity chunks.
9. DB logical state sau restart bằng phase-1 baseline: publication/canonical/snapshot exact; mọi counts/state/history/observation/finding/claim/trigger giữ nguyên; integrity `ok`; issuance false.

## Excluded parser attempt

Restart attempt đầu bị loại trước `world.loadChunk`: clone controller chỉ parse JSON minified trong khi offline receipt là pretty JSON có whitespace. Attempt trả exception, không tạo PASS marker, sau đó shutdown sạch.

Correction clone-only dùng whitespace-aware parser. Trước retry, read-only reseal xác nhận:

- target chunk semantic bằng exact backup trừ target entity;
- target count `2`;
- all-world UUID/code/item UUID hits `0`;
- exact logical DB publication/count/integrity bằng phase-1 baseline.

Offline mutation không chạy lần hai. Excluded artifact: `E:\AI.WORK\itemguard-paper-smoke\excluded\entity-unloaded-destroy-parser-failure.log`.

## Verification

- Contract: RED vì controller chưa tồn tại, rồi `ITEMGUARD_ENTITY_UNLOADED_DESTRUCTION_CONTRACT_GREEN`.
- Focused Java 21: `29/29` PASS.
- Full Java 21: 58 suites, `201/201` PASS, zero failures/errors/skips, `BUILD SUCCESS`.
- Clean rebuild `a14da157…1e7e9` và runtime candidate có `400/400` non-manifest entries byte-identical; target đã khôi phục exact candidate.
- `git diff --check` PASS.
- Pre-runtime `cc/claude-opus-4-8`: correction `ACCEPTED`, `PASS`, blockers none.
- Final `cc/claude-opus-4-8` bị HTTP 429 trên toàn bộ active accounts sau bounded retry.
- Approved fallback `ag/claude-opus-4-6-thinking`: `VERDICT: PASS`, findings none, production change `NO`, evidence gaps none.
- Final verifier bắt buộc restart receipt tồn tại (`assert R.exists()`), nên note optional-marker kế thừa từ review cũ không còn áp dụng.

## Scope limits

Gate chỉ chứng minh external destruction persistence cho một non-stackable entity: không resurrection/duplication và durable ledger giữ nguyên. Không chứng minh ItemGuard quan sát destruction, ghi tombstone, xóa canonical history, xác định nguyên nhân destruction trong game, reclaim issuance, destructive anti-dupe, merge/stackable, multi-player/writer, crash, production hoặc release readiness.

## Evidence

- `E:\AI.WORK\itemguard-paper-smoke\entity-unloaded-destroy-final-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-unloaded-destroy-phase1-evidence.json`
- `E:\AI.WORK\itemguard-paper-smoke\entity-unloaded-destroy-retry-reseal.json`
- `E:\AI.WORK\itemguard-paper-smoke\plugins\ItemGuardSmokeProbe\entity-unloaded-destroy-offline-destroyed.json`
- `E:\AI.WORK\itemguard-paper-smoke\plugins\ItemGuardSmokeProbe\entity-unloaded-destroy-restart.json`
- Review: `docs/reviews/2026-08-26-entity-destruction-while-unloaded-review.md`.
- Archive: `E:\AI.WORK\backups\itemguard-entity-unloaded-destruction-a440fc96-20260826-104836.tar.gz`.
- Archive SHA-256: `c1f071150bcbfba146e4d33913542dcf8265e71898fbcd6bb5a0b7185935901e`.
