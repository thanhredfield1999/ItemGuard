# Controlled double-chest chunk-border authority gate

Ngày: 2026-08-29

## Kết luận

`VERIFIED CONTROLLED PAPER` cho một logical double chest bắc qua đúng ranh giới hai chunk liền kề trên Paper `1.21.11-131`.

Exact production candidate:

- ItemGuard SHA-256: `2e1fb7d8717a02b316bef9c017d09d67b8705c694519433276bd6e316d5adc5e`;
- probe SHA-256: `fb49fcdc9e50d423ca479b745859e3ac81bfc569805e84d259b462aa40d880a9`;
- review-bundle manifest SHA-256: `294987111340724394e645e1e314d6bdeee97c2863e639a6561885dbf45287a2`;
- run token: `97b0a524-048d-44fa-9c6f-94f8b7aabcf2`.

Đây không phải release/deploy/production approval. Overall vẫn `NOT RELEASE READY`.

## Scope được chứng minh

Real `InventoryMoveItemEvent` bắt đầu từ một `DoubleChestInventory` và exact initiating hopper. Raw logical slot được ánh xạ sang đúng physical source half/local slot `3`; source key:

`BLOCK_CONTAINER_SLOT:34486cdf-24f0-4ade-9f92-f820b9605e20:15:-57:-6:3`

Trong first-publication reserve:

1. hopper attempt untagged thực sự xảy ra và bị cancel fail-closed;
2. source và sibling direct ticket đều không được giữ;
3. cả source chunk và sibling chunk nhận real `ChunkUnloadEvent` rồi đều có `World.isChunkLoaded == false`;
4. sau boundary này harness chỉ dùng chunk-loaded/event/journal telemetry, không truy cập live block/state/inventory;
5. proposal cũ `9VGRD4 / 8df1d38f-820b-4095-9922-898db4e253c1` kết thúc `ABORTED/SOURCE_CHANGED`;
6. stopped offline NBT giữ đúng một untagged `DIAMOND_SWORD` tại source physical local slot `3`, sibling/hopper rỗng.

Retry reload cả hai chunk và yêu cầu real `ChunkLoadEvent` cho source lẫn sibling. Proposal mới `H0FBX9 / 9dea2040-cd75-4384-8502-0f188b5d1973` khác code, UUID và publication ID; nó trở thành `PUBLISHED`, tagged transfer được allow đúng một lần và không bị cancel.

Clean restart giữ exact code/UUID/tagged SHA. Fixture cleanup xác nhận đúng một item, ba blocks và source ticket được xóa; stopped NBT không còn ba block entities hoặc item. Canonical publication/tracked/snapshot rows được giữ qua fixture cleanup để seal evidence, rồi operational restore trả toàn clone, JAR, probe, DB và world về baseline.

## Runtime evidence

Authoritative attempt: `attempt-2`.

- prepare DB/NBT: PASS;
- retry DB/NBT: PASS;
- restart DB/NBT: PASS;
- cleanup DB/NBT: PASS;
- bốn Paper phase đều có clean DB close và all-dimensions saved;
- runtime pipeline receipt: four phases PASS, restore PASS;
- independent verifier không import runtime automation và không dùng process exit làm oracle: `143/143` PASS;
- authoritative verdict: `PASS_AUTHORITATIVE_DOUBLE_CHEST_CHUNK_BORDER`;
- independent-verification SHA-256: `5483aa51453efdd7b33ce207ee43cda1b5aa6fddd70cf63877eff8e4613e1d06`.

Stopped clone sau restore:

- ItemGuard JAR SHA-256 `5fc2512f57fb4c5d35e6fbac3a889b3263945340d790f9ccb0d26d05133b8e3c`;
- probe SHA-256 `0fd75b88368dd66f0b0294312a52404f5cd7ee340400da4f18b3ecf7ce495e03`;
- DB SHA-256 `86bf625f9eca20b9803971d8828f709c8c7b55304ec9a6ef0b2e688075de7eee`;
- DB logical SHA-256 `944413c7d472f2a3af42ef42e998449ba18c0dc080529295de5dd9b13682f3e0`;
- integrity `ok`, journal mode `delete`, triggers `0`, sidecars absent;
- controlled ports `57485/36104/57486` closed;
- operational backup archive SHA-256 `f5e144994ae5bd3596dbc1766aea719135222808f19a3474e872b87f4fe08608`, read-back `191` files.

## Review chain

Claude route `cc/claude-opus-*` không trả verdict vì provider từ chối HTTP 400 extra-usage/quota. Đây được ghi là review infrastructure unavailable, không phải product PASS/FAIL.

Independent fallback reviews bảo tồn đầy đủ audit chain:

1. attempt 2 `BLOCKED`: false static oracle, wrong attempt defaults, unsafe cleanup, thiếu `taggedCancelled == 0`;
2. attempt 3 `BLOCKED`: runtime namespace có thể chọn probe cũ;
3. attempt 4 `BLOCKED`: pipeline gọi child scripts/toolchain mutable ngoài bundle;
4. attempt 5 `BLOCKED`: file ngoài manifest và executable identity chưa khóa đủ;
5. attempt 6 `BLOCKED`: restore failure có thể để process exit `0`;
6. attempt 7 `PASS_FOR_CONTROLLED_PAPER`: exact file set/hashes, automation/toolchain/runtime identity, restore-exit semantics và product oracles đều PASS.

Attempt 1 được giữ bất biến là `NON_AUTHORITATIVE_HARNESS_TOPOLOGY_FAILURE`, `productVerdict=NOT_ISSUED`. Topology anchor cũ không làm source chunk thật sự unloaded theo Bukkit. Restore attempt 1 PASS.

## Residual risk

Gate này không chứng minh:

- natural break/place hoặc physics/piston timing;
- crash/tombstone/destruction/relocation ở các cửa sổ khác;
- hơn hai hoppers, density hoặc performance scale;
- non-chest/unopened/custom inventory và throwing accessor;
- Folia/regionized threading;
- multi-server/multi-writer/shared filesystem;
- external absence adapters, issuance hoặc destructive quarantine;
- production deployment/verification.

Không commit, push, deploy hoặc restart production trong gate này.
