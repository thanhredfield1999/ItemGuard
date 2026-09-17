# Controlled ItemGuard command/GUI UX UAT — 2026-08-27

> Historical first attempt. Verdict `PARTIAL/INCONCLUSIVE` bên dưới được bảo tồn để giữ audit chain. Authoritative rerun sau BotChecker P0/P1 fixes đã PASS; xem `2026-08-27-controlled-command-gui-ux-uat-rerun.md` và archive SHA-256 `b64914fe00b7025ff644638a3d114a7a897f999325dc7d49448d254b6bf99a00`.

## Kết luận

`PARTIAL VERIFIED / OVERALL INCONCLUSIVE` cho command/GUI UX trên controlled Paper clone.

Candidate exact `06cf01c7c589545838b29833ed158b31954f850d22e080750179e9b60597b037` đã:

- Java 21 focused UX/lifecycle delta `12/12` PASS;
- full clean Maven `266/266` PASS;
- exact Opus 4.8 source review và meta-null correction review `PASS`;
- controlled Paper `1.21.11-131`, protocol `774` xác minh permission boundary, self-history/detail/back/parent-browser flow, staff stats và `#CODE` search output.

Không báo toàn bộ UAT PASS vì BotChecker hiện không decode được Adventure GUI components và có timing/oracle limitations. Staff browser/filter/pagination `28+1` chưa được chạy tới cuối. Production không bị sửa/deploy/restart; release vẫn `NO`.

## Artifact và environment

- Source repo: `E:/AI.WORK/ItemGuard`, branch `main`, head baseline `1b7d05517f2d95d979a202971a67a08c54ad153d`.
- Controlled clone: `E:/AI.WORK/itemguard-paper-smoke`.
- Paper: `1.21.11-131-ver/1.21.11@6d5b910`.
- Java: `21.0.4`.
- Minecraft protocol: `774`.
- Candidate JAR: `target/ItemGuard-1.0.0.jar`.
- Candidate SHA-256: `06cf01c7c589545838b29833ed158b31954f850d22e080750179e9b60597b037`.
- Exact full source review: `cc/claude-opus-4-8 PASS`, dev UAT ready `YES`, production ready `NO`.
- Exact correction review cho `getItemMeta()` guards: `cc/claude-opus-4-8 PASS`, production ready `NO`.

## Java verification

```text
./mvnw.cmd -Dtest=GuiItemMetaSafetyWiringContractTest,... test --no-transfer-progress
```

Kết quả cuối cho focused delta: `12/12 PASS`.

```text
./mvnw.cmd clean test package --no-transfer-progress
```

Kết quả cuối: `266/266 PASS`, `BUILD SUCCESS`; `git diff --check` PASS.

Tight RED cuối:

- `GuiItemMetaSafetyWiringContractTest` RED vì hai click handlers có unguarded `clicked.getItemMeta().getPersistentDataContainer()`;
- fix tối thiểu dùng local `ItemMeta`, `meta == null` return fail-closed ở cả browser và main-browser handlers;
- correction review xác nhận event đã cancelled trước handler, nên meta-less item không thể leak/mutate.

## Fixture

Clone được backup toàn bộ trước UAT:

- archive `E:/AI.WORK/backups/itemguard-ux-preuat-20260827-050135.tar.gz`;
- SHA-256 `c06bbff047083ec8f93a79a01236a5962f08871a54c63edadac20611b4af4166`;
- `1783` archive entries, read-back verified.

Fixture offline seed trong SQLite transaction:

- member `IGMemberUX`, UUID `4e465b1f-8d7c-32e4-9327-a03969c7be2c`, non-op, one tracked item `UM0001`, one history;
- staff `ItemGuardStaffUX`, UUID `e2a90753-a001-3727-abf5-28b2b8f7e45b`, op, exactly `29` tracked `DIAMOND_SWORD` items `UXS001..UXS029`;
- `UXS001` có hai history rows;
- seeded delta `30` items / `3` history, schema `7`, SQLite integrity `ok`;
- smoke probe cũ disabled trong clone để không chạy fixture ngoài scope;
- `anti-dupe.enabled=false`, reclaim issuance vẫn đóng.

## Startup/shutdown

Startup:

- candidate hash trong `plugins/ItemGuard.jar` khớp exact source artifact;
- ItemGuard enable và SQLite schema open thành công;
- Paper `Done (22.233s)`;
- TCP `57485`, query `36104` đúng clone;
- không có ItemGuard/Paper exception, linkage error hoặc DB load error.

Shutdown:

- tất cả clients disconnect;
- `ItemGuard Disabling`;
- `Database connection closed`;
- all dimensions/chunks/RegionFile I/O saved;
- ports `57485/36104/18085/18086` đóng;
- OS không còn Paper PID;
- Hermes PTY wrapper giữ stale working directory nên phải đóng wrapper sau khi JVM thực đã exit; không force-kill JVM Paper.

## Runtime evidence

### Permission boundary và member journey

Final member run: `29fa567c-00cd-4bd9-b1a0-7ee4dd01ab08`.

Report status: `FAIL`, `23/24 PASS`; giữ nguyên, không đổi thành PASS.

Verified prefix:

1. member health/food `20/20`, GUI closed khi join;
2. direct `/igstats` và `/igsearch` bị Paper manifest permission ẩn với `Unknown or incomplete command`;
3. `/ig browser` bị ItemGuard deny;
4. `/ig history #UXS001` bị deny;
5. `/ig history ItemGuardStaffUX` bị deny trong khi staff target thật đang online;
6. `/ig history` self mở history GUI;
7. slot history mở detail;
8. detail back về history;
9. history parent back về own-items browser;
10. own-items GUI chứa expected control/item slots;
11. close slot được inspect và click authorized.

Failure cuối:

- `assert_state gui=closed` chạy ngay sau click và thấy GUI vẫn open;
- BotChecker nhận `gui_close` ngay sau step fail;
- source BotChecker xác nhận `assert_state` kiểm một lần, không poll theo `timeoutMs`;
- attribution: `INCONCLUSIVE_HARNESS_TIMING`, không phải confirmed ItemGuard close bug.

### Staff command journey

Final staff run: `d2358257-5e68-4087-bf5e-4f2a8e5ba86e`.

Report status: `FAIL`, `5/6 PASS`; giữ nguyên.

Verified prefix:

- `/igstats` trả tổng item/history/online/dupes/database;
- `/igsearch #UXS001` nhận format `#CODE` và trả exact item `UX Staff Sword 001`, owner `ItemGuardStaffUX`, `Phát hiện: 1 lần`.

Failure:

- step kế tiếp chờ ASCII `Phat hien` sau khi exact Unicode line đã đến ở step trước;
- `wait_for_text` chỉ đọc events từ đầu step hiện tại;
- attribution: lỗi scenario ASCII + BotChecker thiếu composite/lookback matcher;
- staff browser/filter/pagination/history flow không được chạy tiếp, do đó `NOT VERIFIED runtime`.

### Excluded/diagnostic runs

- `90cbd399-a064-4076-a780-628bfc0b421f`: RED oracle ban đầu mong message ItemGuard cho direct alias; Paper đúng ra ẩn command ở manifest permission. Không phải product bug.
- `303b0472-42ef-4807-bbab-dcda93ea9d5d`: GUI mở đúng nhưng title/custom-name/lore đều `[object Object]`; BotChecker component decoder limitation.
- `4674399a-fee3-4c36-888f-b195f93c0448`: stale-window safety chặn click đúng khi GUI generation đổi 9 ms sau assertion đọc GUI cũ. Không bỏ guard.

## BotChecker gap report

Chi tiết:

`E:/AI.WORK/botcheckerminecraft-botchecker/docs/ITEMGUARD_UX_UAT_BOTCHECKER_GAPS_2026-08-27.md`

Ưu tiên:

1. Adventure component decoder;
2. polling `assert_state`;
3. generation-aware GUI transitions;
4. composite text matcher/lookback + Unicode normalization;
5. top/total slot model + material/absence/cardinality selectors;
6. live multi-account orchestration;
7. typed `INCONCLUSIVE_HARNESS` attribution.

## Evidence seal

- directory: `E:/AI.WORK/evidence/itemguard-ux-uat-20260827/`;
- archive: `E:/AI.WORK/evidence/itemguard-ux-uat-20260827.tar.gz`;
- archive SHA-256: `ac6840fe4ea13279042d09c52781c431ec10265e18834b762a0b048e7b79c3da`;
- `14` files / `15` tar members, read-back verified;
- archive giữ cả report FAIL và diagnostic attempts để không mất audit chain.

## Restore proof

Sau seal, toàn clone được restore từ full pre-UAT archive, không chỉ chép DB/JAR:

- restored ItemGuard JAR `5fc2512f57fb4c5d35e6fbac3a889b3263945340d790f9ccb0d26d05133b8e3c`;
- restored DB `86bf625f9eca20b9803971d8828f709c8c7b55304ec9a6ef0b2e688075de7eee`;
- restored `ops.json` `49d4e3bcacf6b22be0b6ff14f4fc2ddf53f65ba3759d0c6be402cde87acfb8bb`;
- restored smoke probe `0fd75b88368dd66f0b0294312a52404f5cd7ee340400da4f18b3ecf7ce495e03`;
- all hashes match critical rollback set;
- disabled-probe UAT rename absent;
- session lock exclusive;
- quarantine copy removed only after all comparisons passed.

## Verdict boundary

### VERIFIED

- current candidate source/unit/build `266/266`;
- independent source review PASS;
- controlled Paper startup/disable/DB close;
- member permission boundary;
- self history/detail/back/parent-browser flow;
- staff stats và code search output;
- exact fixture UUID/protocol linkage;
- clean clone restore.

### NOT VERIFIED

- exact GUI title/custom-name/lore presentation runtime;
- staff browser/filter/pagination `28+1`/history full journey;
- rapid quit/reopen stale async result ordering;
- final close dưới polling oracle hợp lệ;
- production.

### Release

`NOT RELEASE READY`. Candidate đủ tiếp tục controlled UAT sau khi BotChecker P0/P1 được sửa hoặc bằng manual client UAT được người dùng thao tác. Không deploy/restart production nếu chưa có approval riêng.
