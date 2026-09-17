# Controlled Paper — block-container replacement trong pre-write reserve

Ngày: 2026-08-28

## Kết luận

`VERIFIED` cho exact block-container source bị thay `CHEST → BARREL → CHEST`, sau đó dựng lại cùng serialized item bytes tại cùng world/block/local slot trong in-flight first-publication reserve:

- ItemGuard SHA-256: `2e1fb7d8717a02b316bef9c017d09d67b8705c694519433276bd6e316d5adc5e`;
- probe SHA-256: `bfa5126841c3ea7f74e7ac299238901de3f1ff7bf5112d588747169571c69294`;
- Paper: `1.21.11-131`;
- token: `3ce2a813-b27e-4f75-8f5b-d37ba7700e60`;
- authoritative attempt: `attempt-2`;
- verdict: `PASS_AUTHORITATIVE_CONTAINER_BLOCK_REPLACEMENT`;
- independent verifier: `199/199` PASS.

Overall ItemGuard vẫn `NOT RELEASE READY`. Production không được truy cập, deploy hoặc restart.

## Mục tiêu falsifier

Gate chunk-unload trước đó chứng minh loaded authority mất hoàn toàn thì proposal phải abort. Matrix này giữ các thuộc tính bề mặt giống nhau nhưng đổi physical object:

1. cùng source key;
2. cùng block type cuối `CHEST`;
3. cùng local slot `3`;
4. cùng serialized source digest;
5. chunk vẫn loaded và có plugin ticket;
6. tile/item vật lý đã bị phá và dựng lại.

Nếu correction chỉ kiểm location/type/digest mà không kiểm physical handle, proposal đầu có thể publish nhầm vào source mới. Kết quả `ABORTED/SOURCE_CHANGED` khi digest/source key vẫn bằng nhau phân lập physical-handle authority check.

## Source/build/review gate

Production candidate không đổi từ gate chunk-unload:

- ba RED contracts cho authority/re-resolution/public bypass;
- focused Java `28/28` PASS;
- full Java 21 `292/292` PASS;
- probe build PASS;
- probe static contract `22/22` PASS;
- `git diff --check` PASS.

Exact `cc/claude-opus-4-8` review:

- verdict `PASS_FOR_CONTROLLED_PAPER`;
- blockers `[]`;
- prompt SHA-256 `3f63e6deb5865498c78cd9cefd412602f60ae10218a8f8e700ec25b57d93e4cb`.

Reviewer yêu cầu exact row/source/digest/cardinality, replacement ordering, offline NBT, retry/restart/cleanup và restored-clone proof. Authoritative attempt đóng đủ.

## Authoritative matrix

### Prepare: replacement trong reserve

Physical source:

`BLOCK_CONTAINER_SLOT:34486cdf-24f0-4ade-9f92-f820b9605e20:312:-57:-8:3`

Real hopper event:

- untagged attempts/cancelled/allowed: `1/1/0`;
- source `CHEST`, intermediate `BARREL`, final `CHEST`;
- chunk loaded throughout;
- exact reconstructed source digest: `1abb83bfd2a51da409c95284a88b74d81d88fa0005c4f6486aac6d7cfabcbe0a`.

First proposal:

- code `MN9NS9`;
- item UUID `86c0b87e-f50e-4ce7-af5f-f82cecab4c6a`;
- publication `24fc5ad6-a69d-413b-a6b5-93b210180e5e`;
- state/detail `ABORTED/SOURCE_CHANGED`.

Ordering:

- created: `1787923458266`;
- replacement started: `1787923458400`;
- BARREL observed: `1787923458411`;
- CHEST + exact item reconstructed: `1787923458416`;
- abort updated: `1787923471849`.

`created_at ≤ replacementStartedAt ≤ barrelAt ≤ reconstructedAt ≤ updated_at`.

Stopped DB delta:

- publications `+1`;
- ABORTED `+1`;
- PUBLISHED/tracked/snapshots `+0/+0/+0`;
- PREPARED `0`;
- history/observations/findings/stats/claims unchanged;
- exact controlled trigger read back then dropped;
- integrity `ok`, `journal_mode=delete`.

### Offline NBT

Sau clean stop, CPython 3.11.15 parser đọc exact region:

- current source block `minecraft:chest`;
- hopper block `minecraft:hopper`;
- chest có đúng một `minecraft:diamond_sword` tại slot `3`;
- probe token hit `1`;
- ItemGuard identity key hit `0`;
- hopper entries `0`.

Region SHA-256: `9e0d5f1b28e30453643ade027509bdc645d4d35487e9a309e79a799f06e53523`.

### Retry

Fresh retry trên reconstructed source:

- untagged attempts/cancelled/allowed: `1/1/0`;
- tagged allowed/cancelled: `1/0`;
- same exact source key/digest;
- distinct proposal/code/UUID;
- code `KJOMHK`;
- UUID `dc4718bc-6f8d-46f7-9409-d98c86be28d7`;
- state/detail `PUBLISHED/null`;
- tagged SHA `8ddeecb305ee95fd1f45907381a02c1400273de7db36a09b15dee625f2931025`;
- canonical/snapshot/PUBLISHED `+1/+1/+1` và zero unrelated delta.

### Restart, cleanup và restore

- clean restart giữ exact code/UUID/tagged SHA;
- restart DB rows/counts/byte+logical hashes bằng retry;
- cleanup xóa đúng `1` item và `2` blocks;
- cleanup DB rows/counts/byte+logical hashes bằng restart;
- bốn Paper phases đều clean DB close + all dimensions saved;
- operational restore PASS.

Operational backup:

- archive `E:/AI.WORK/backups/itemguard-container-replace-pre-runtime-20260828-184451.tar.gz`;
- SHA-256 `2b87baffb6159558138f3f87b577a2728c7788a7218d34845bc1b1e317cad8a9`;
- `191` files read-back verified.

Restored clone:

- ItemGuard SHA `5fc2512f57fb4c5d35e6fbac3a889b3263945340d790f9ccb0d26d05133b8e3c`;
- probe SHA `0fd75b88368dd66f0b0294312a52404f5cd7ee340400da4f18b3ecf7ce495e03`;
- DB byte SHA `86bf625f9eca20b9803971d8828f709c8c7b55304ec9a6ef0b2e688075de7eee`;
- DB logical SHA `944413c7d472f2a3af42ef42e998449ba18c0dc080529295de5dd9b13682f3e0`;
- integrity `ok`, `journal_mode=delete`, triggers `0`, sidecars absent;
- ports `57485/36104/57486` closed.

## Independent verification

Verifier được tạo/chạy bằng đúng Hermes model route `cc/claude-opus-4-8` qua provider `custom:keyden`, sau đó parent chạy lại trực tiếp:

- `199/199` assertions PASS;
- không import runtime scripts;
- không tin `runtime-verdict.json` đơn lẻ;
- đọc preserved DB row payload, markers, NBT, logs, review receipt và restored clone;
- verdict `PASS_AUTHORITATIVE_CONTAINER_BLOCK_REPLACEMENT`.

Receipt: `attempt-2/independent-verification.json`.

## Audit chain

- `attempt-1`: `NON_AUTHORITATIVE_HARNESS_TRANSPORT_FAILURE`; Mineflayer username dài `18` vượt protocol limit `16`, server từ chối hello trước spawn/command; zero phase PASS; restore PASS.
- `attempt-2`: username `IGContReplace` dài `13`, fresh token; exact candidate/probe/review bất biến; 4 phases + independent verifier PASS.

Không relabel attempt 1 thành production failure.

## Residual scope

Không suy gate này sang:

- natural player break/place và physics-event semantics;
- double-chest/chunk-border replacement hoặc unload;
- hơn hai hoppers, density và performance;
- non-chest/custom inventories, unopened scanning, throwing accessors;
- Folia/regionized threading;
- multi-server/multi-writer/shared filesystem;
- broader crash/unload stress, relocation/tombstone;
- production deployment/verification.

Evidence root: `E:/AI.WORK/evidence/itemguard-container-block-replacement-20260828/`.

External sealed archive receipt (tạo sau archive, không tự nhúng vào archive):

- archive: `E:/AI.WORK/evidence/itemguard-container-block-replacement-20260828.tar.gz`;
- SHA-256: `85791ac2ed2fa02d27e3ce60c7a0ebab75bac17aced1cc18fe8130545a14c6a1`;
- `60` members (`59` payload checksums + checksum manifest), read-back verified.
