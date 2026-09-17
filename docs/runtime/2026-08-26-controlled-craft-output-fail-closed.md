# Controlled craft output fail-closed

Ngày: 2026-08-26

## Kết luận runtime

`VERIFIED CONTROLLED PAPER + FINAL EXACT OPUS 4.8 PASS`.

Run token: `33c4beab-e610-40ae-9f06-03550a84a87f`.

Production chưa deploy/restart/verify. Reviewer xác nhận không cần candidate change; live chỉ nhận fix sau deployment được phê duyệt riêng. Project vẫn `NOT RELEASE READY`.

## Contract

Vì Paper `CraftItemEvent` không cung cấp aggregate shift-output transaction an toàn:

- tagged output ready: cancel để không copy UUID;
- tagged partial/corrupt: cancel;
- eligible untagged output: cancel;
- chỉ untracked untagged output được phép;
- không mutate result, tag, publish hoặc ghi DB trong craft event.

## Artifact

- runtime candidate: `763ec6c78996b3c18a898e2f46af7d444341890eb77fff29149d5e016253a667`;
- rebuilt JAR: `d8a0919f655d4452f8a6269ef848e5636ddf16dc7f3bfc6a202e65ed819b5cec`;
- probe: `e100256f70005e94719f2c646c8a76d26387fb0a1f461c0a850faf3c3e2ee685`;
- `382/382` non-META-INF entries runtime/rebuild byte-identical.

## Real normal click

Custom clone-only recipe: `IRON_NUGGET →` named `DIAMOND_SWORD`.

Mineflayer gửi real result-slot `0`, mode `0`:

- LOWEST event: `1`;
- MONITOR cancelled: `1`;
- matrix ingredient: `1`, không giảm;
- acquired sword: `0`;
- exact ItemGuard denial count sau phase: `1`.

## Real shift click

Mineflayer gửi result-slot `0`, mode `1`:

- LOWEST shift event: `1`;
- MONITOR cancelled: `1`;
- matrix ingredients: `3`, không giảm;
- acquired sword: `0`;
- normal returned ingredient được account/remove đúng `1` trước phase;
- cumulative exact ItemGuard denial count: `2`.

## Restart

Sau clean stop/start:

- player inventory: `IRON_NUGGET x3`;
- fixture sword: `0`;
- persisted exact crafting table tồn tại tại `(-3,-60,5)`;
- location artifact pin run token + world UUID + exact block, không recompute theo player position.

## DB oracle

Baseline và post-restart/post-cleanup DB hash đều:

`86bf625f9eca20b9803971d8828f709c8c7b55304ec9a6ef0b2e688075de7eee`.

Exact zero delta cho tracked items, snapshots, publications/PUBLISHED/PREPARED/ABORTED, history, observations, findings, duplicate stat, claims và triggers. Integrity `ok`.

## Cleanup

- removed ingredients: `3`;
- removed fixture swords: `0`;
- removed exact crafting table: `1`;
- location artifact removed: `true`;
- ports/process clone closed; clean DB/world shutdown.

## Verification

- focused: `19/19`;
- full Java: `66` suites / `232/232`;
- `git diff --check`: PASS;
- precleanup seal: PASS;
- postcleanup seal: PASS.

## Giới hạn

Chưa chứng minh transactional craft issuance; gate cố ý fail-closed. Chưa suy rộng sang smithing/anvil/stonecutter, custom recipe plugins mutating sau HIGH, multi-server/writer, crash/scale hoặc production.
