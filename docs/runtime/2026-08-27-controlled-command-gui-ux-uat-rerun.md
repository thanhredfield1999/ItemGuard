# Controlled ItemGuard command/GUI UX UAT rerun — 2026-08-27

## Kết luận

`VERIFIED controlled command/GUI UX UAT PASS` trên isolated Paper clone cho candidate:

`06cf01c7c589545838b29833ed158b31954f850d22e080750179e9b60597b037`

Authoritative final runs:

- member `d26d730e-6bff-4471-8c96-5d104d811f62`: `24/24 PASS`;
- staff `40698a9d-a0ff-4422-ac9a-3b5e21a1a33b`: `38/38 PASS`, dùng fixture đúng `29` item và pagination `28+1`.

Rerun xác minh permission boundary, Unicode command output, Adventure GUI title/name/lore, generation-aware navigation, exact top-inventory cardinality, filter, history, detail, back và final close. Không có fixed sleep trong member/staff scenario chính.

Đây không phải release approval. Production không bị deploy, restart hoặc mutate; production scale/performance và các release gate ngoài command/GUI UX vẫn chưa verified.

## Environment và binding

- ItemGuard source repo: `E:/AI.WORK/ItemGuard`.
- Controlled clone: `E:/AI.WORK/itemguard-paper-smoke`.
- Paper: `1.21.11-131`.
- Minecraft protocol: `774`.
- Controlled ports: server `57485`, query `36104`, BotChecker `18085/18086` loopback-only.
- Candidate JAR SHA-256: `06cf01c7c589545838b29833ed158b31954f850d22e080750179e9b60597b037`.
- BotChecker Git commit: `3339f2229679a0cf78aa31b8a35ea9ea2ae2d29d` với dirty state explicit.
- BotChecker source fingerprint: `fc7d4d7e1986bdfef35cc8dfac879ef97ed07344b5f84c1d7bc7a0506c4b2a10`.
- Node: `v24.14.0`.
- `anti-dupe.enabled=false`; reclaim issuance không được mở.

BotChecker final offline gate trước rerun:

- typecheck PASS;
- full suite `318 pass / 0 fail / 2 intentional Windows signal skips` (`320` tests);
- build PASS;
- `git diff --check` PASS.

Mốc `318/0/2` là exact runtime-bound source snapshot có fingerprint `fc7d4d7e…2a10`. Sau khi các slice song song ngoài UAT hoàn tất và docs được cập nhật, ordered gate trên current tree chạy lại PASS với `326 pass / 0 fail / 2 intentional Windows signal skips`, typecheck/build/diff-check PASS. Mốc hậu kỳ này không được dùng để hồi tố source fingerprint hoặc archive runtime đã seal.

ItemGuard candidate source gate đã có trước rerun:

- focused lifecycle/meta `12/12 PASS`;
- full Java 21 `266/266 PASS`;
- Maven `BUILD SUCCESS`;
- exact Opus 4.8 UX review và meta-null correction `PASS`, production ready `NO`.

## Fixture và preflight

Pre-UAT rerun backup:

- `E:/AI.WORK/backups/itemguard-ux-rerun-preuat-20260827-124601.tar.gz`;
- SHA-256 `23815e432244d6f35e01f6f74bd0b4c69367b72661e91dfc43682c8bd0987210`;
- đúng `9` mutable entries: JAR, DB, config, messages, ops, smoke probe và ba rerun scenarios.

Exact historical seed/orchestration được tái sử dụng. SQLite transaction tạo:

- member `IGMemberUX`, one item `UM0001`, one history;
- staff `ItemGuardStaffUX`, exactly `29` `DIAMOND_SWORD` items `UXS001..UXS029`;
- `UXS001` có hai history rows;
- tổng delta `30` item / `3` history;
- `PRAGMA integrity_check = ok`.

Preflight khóa exclusive runtime, candidate/deployed hash, backup, probe rename, DB collision, ports và process ownership trước startup. Smoke probe bị disable bằng rename đảo ngược được trong UAT.

## BotChecker runtime RED → GREEN

### Protocol 774 prismarine-NBT component decoder

Initial rerun `d9feae08-45ee-4760-9e6a-462edf09809c` giữ `FAIL`: GUI type/material/top-slot đúng nhưng title/custom-name/lore rỗng. Bounded read-only shape probe chứng minh Paper 1.21.11 gửi text dưới dạng prismarine-NBT tag `{type, value}`.

TDD:

1. captured protocol-774 prismarine-NBT fixture RED (`actual ''`);
2. decoder được sửa tối thiểu để unwrap bounded `compound/list/string` trước Adventure traversal, giữ recursion/text/lore budgets;
3. regression GREEN;
4. full decoder `10/10`, focused P0/P1 `103/103`, full gate cuối PASS.

Không quy RED này thành ItemGuard product defect.

### Staff history cardinality oracle

Initial staff rerun `969fd096-b849-4092-a9ca-9c2b67d5207a` giữ `FAIL`: exact GUI snapshot có hai history rows ở slots `10/11`, nhưng selector owner-name đếm cả nút `Quay lại` vì control cũng chứa owner trong lore.

Scenario correction không hạ acceptance: cardinality vẫn `exactly: 2`, nhưng dùng marker riêng của history row (`Click để xem chi tiết`) thay cho owner-name selector quá rộng. Final staff rerun PASS.

Không quy RED này thành ItemGuard hoặc BotChecker matcher defect.

## Member authoritative journey

Run: `d26d730e-6bff-4471-8c96-5d104d811f62`.

Report: `24/24 PASS`, protocol `774`, final GUI closed.

Verified:

1. health/food `20/20`, GUI closed khi join;
2. direct staff aliases bị Paper manifest permission ẩn;
3. member bị deny browser, code history và other-player history;
4. self-history mở đúng GUI text đã decode;
5. detail open và back về history bằng generation-aware oracle;
6. parent back về own-items browser;
7. expected own item/control tồn tại trong top inventory;
8. close click qua inspect-before-click fail-closed guard;
9. polling `assert_state` xác minh final GUI closed.

Report artifact:

`E:/AI.WORK/evidence/itemguard-ux-uat-rerun-20260827-124601/member-report-v2.json`

## Staff authoritative journey

Run: `40698a9d-a0ff-4422-ac9a-3b5e21a1a33b`.

Report: `38/38 PASS`, protocol `774`, final GUI closed.

Verified:

1. `/igstats` output;
2. `/igsearch #UXS001` bằng composite matcher trong cùng event, exact Unicode NFC `Phát hiện`;
3. main browser title/custom-name/lore decode;
4. page 1 exactly `28` `diamond_sword` top-grid items;
5. material filter reopen và page 1 vẫn exactly `28`;
6. page 2 exactly `1`, previous control và absence oracle cho remainder;
7. back về page 1 exactly `28`;
8. player history exactly `2` event rows;
9. history detail open, back và history cardinality restored;
10. parent browser return exactly `28`;
11. code-only history exactly `2`;
12. close control và final GUI closed.

Report artifact:

`E:/AI.WORK/evidence/itemguard-ux-uat-rerun-20260827-124601/staff-report-v3.json`

## Startup, logs và shutdown

- Candidate hash read-back exact trước startup.
- ItemGuard enabled, DB opened và Paper reported `Done (22.202s)`.
- Không có ItemGuard/Paper exception, linkage error hoặc DB load error trong journeys.
- Hai BotChecker APIs chỉ bind `127.0.0.1`.
- Clients disconnect trước Paper stop.
- Clean `stop` command observed; ItemGuard disabled và `Database connection closed`.
- All dimensions/chunks/RegionFile I/O saved.
- JVM listener/PID của clone biến mất; Hermes PTY wrapper được đóng sau khi child exit đã được xác minh.
- Post-stop DB integrity `ok`, fixture `30` item / `3` history trước restore.

## Evidence seal

Evidence directory:

`E:/AI.WORK/evidence/itemguard-ux-uat-rerun-20260827-124601`

Archive:

`E:/AI.WORK/evidence/itemguard-ux-uat-rerun-20260827-124601.tar.gz`

Hashes:

- archive SHA-256 `b64914fe00b7025ff644638a3d114a7a897f999325dc7d49448d254b6bf99a00`;
- seal manifest SHA-256 `6af7da9d39457a78cd8833c1203070d0a30b3ec7cdd3eb8cb6585af0684b2016`;
- selected oracle evidence SHA-256 `13da46e2c330b5b80da469ca680ead65430121f77ba3d03b5dbd362d3ee414e9`.

Archive contains `34` regular files / `35` tar members. Independent verifier checked:

- no link, absolute path hoặc `..` traversal;
- manifest, final reports, selected oracle, restore read-back, Paper log và post-UAT DB inside archive byte-match external files;
- manifest verdict `PASS`, member `24`, staff `38`;
- preserved RED reports remain in audit chain.

Historical archive `itemguard-ux-uat-20260827.tar.gz` was not modified.

## Notion receipt

- Updated the existing ItemGuard task only; no duplicate page was created.
- Marker `[ITEMGUARD_UX_UAT_RERUN_20260827_124601]` read back exactly once across `176` top-level blocks / `2` API pages.
- Task Status remains `In Progress`; Updated is `2026-08-27` because broader release risks remain open.
- Receipt: `E:/AI.WORK/evidence/itemguard-ux-uat-rerun-20260827-124601-notion-receipt.json`, SHA-256 `afa545b2b0fe6d433a7d722ad13f609034db75635356df8a3fd5d4e41adbf7a9`.

## Restore proof

Clone was restored from the rerun backup after clean stop and evidence copy:

- deployed ItemGuard JAR `5fc2512f57fb4c5d35e6fbac3a889b3263945340d790f9ccb0d26d05133b8e3c`;
- DB `86bf625f9eca20b9803971d8828f709c8c7b55304ec9a6ef0b2e688075de7eee`;
- config `61e562389f7a0e5dfd08f2da6d7b28408a8d67cec3401325c3e4167685b6d609`;
- messages `1150bf0f6eb7ca080e125ac06e244b06e4486d3814be2db8aee98e7b208fe7e7`;
- ops `49d4e3bcacf6b22be0b6ff14f4fc2ddf53f65ba3759d0c6be402cde87acfb8bb`;
- smoke probe enabled, SHA-256 `0fd75b88368dd66f0b0294312a52404f5cd7ee340400da4f18b3ecf7ce495e03`.

Post-restore verification:

- SQLite integrity `ok`;
- fixture item/history counts `0/0`;
- disabled-probe rename absent;
- ports `57485/36104/18085/18086` closed;
- clone offline.

## Verdict boundary

### VERIFIED

- candidate source/unit/build and prior independent UX review;
- controlled Paper command/GUI member `24/24`;
- controlled Paper staff full browser/filter/pagination/history journey `38/38`;
- protocol-774 Adventure title/custom-name/lore decode in live journey;
- exact top-inventory `28+1` cardinality and absence semantics;
- final close through polling/generation-aware oracles;
- clean DB close, evidence seal and byte-exact clone restore.

### NOT VERIFIED

- production;
- production scale/performance;
- rapid quit/reopen stale async result ordering beyond this journey;
- release readiness outside command/GUI UX scope;
- unrelated open anti-dupe/persistence/external/issuance gates listed in `CURRENT_STATE.md` and `docs/RISK_REGISTER.md`.

## Release

`NOT RELEASE READY`. Controlled command/GUI UX gate is now PASS, but production deployment/restart still requires separate explicit approval and all remaining release gates remain authoritative.
