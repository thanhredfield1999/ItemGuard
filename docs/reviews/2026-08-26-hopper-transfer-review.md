# Hopper transfer independent review chain

Ngày: 2026-08-26

## Kết luận

Authoritative final exact `cc/claude-opus-4-8`: `PASS`.

- required fixes: none;
- production change: `NO`;
- release ready: `NO`.

## Audit chain

1. Initial pre-runtime exact Opus 4.8: `BLOCK`.
   - cooldown test vacuous vì tìm sai call string;
   - marker chưa bind actual production publication;
   - source key do fixture tự tổng hợp;
   - slot4 attribution chưa đủ grounded.
2. Correction:
   - non-vacuous cancellation-before-cooldown assertion;
   - fresh item untagged assertion;
   - independent DB PUBLISHED row/source-key/SHA bind;
   - single physical item/restart destination observation oracle.
3. Correction exact Opus 4.8: `PASS`, runtime authorized.
4. Historical run `c684074c-...`: final exact Opus 4.8 `BLOCK`.
   - reviewer chain từng gọi sai proof `PUBLISHED/SPAWN`, trong khi actorless publication đúng contract có history `0`;
   - tagged allow counters chỉ `>=1`, chưa khóa exact historical event cardinality.
5. Final correction:
   - fixture, bot, DB sealer và structural contract đều yêu cầu tagged source/destination exact `1/1`;
   - proof được sửa thành actual PUBLISHED row only, identity history exact empty;
   - corrected probe SHA `d5eb8a81…165cb1`.
6. Final correction exact Opus 4.8: `PASS`, fresh rerun authorized.
7. Fresh authoritative run `c1331bad-...`: all prepare/publication/restart/cleanup seals PASS.
8. Authoritative final exact Opus 4.8: `PASS`, required fixes none.

## Reviewer-confirmed evidence

- Production policy/ordering fail-closed và cooldown chỉ dedupe next-tick source scan.
- Exact event telemetry `1 cancelled untagged`, `0 untagged allowed`, tagged source/destination exact `1/1`.
- Actual PUBLISHED row source chest/local4, owner null, exact code/UUID/SHA; history zero đúng actorless contract.
- Restart exact one destination observation slot0; no duplicate finding/stat.
- Cleanup one item/three blocks; observations zero; integrity ok.
- Runtime candidate/probe hashes pin trong fresh baseline và seals.
- Runtime/rebuild `408/408` non-manifest entries byte-identical.

## Historical evidence classification

- `HARNESS_ONLY_PROBE_STARTUP_CONFIG`: probe disabled trước command; DB/marker zero mutation.
- `HARNESS_ONLY_ORACLE_MISMATCH`: first sealer sai expected history/cột schema; không phải product failure.
- Historical run `c684074c-...`: valid observed behavior nhưng evidence contract bị final reviewer `BLOCK`; không authoritative.

## Residual limits

- hopper minecart;
- double-hopper concurrency;
- chunk unload mid-transfer;
- multi-server/writer;
- scale/production;
- standalone JVM/MockBukkit single-chest slot4 resolver test.

Không dùng review này để bật issuance/destructive action hoặc claim production verification.
