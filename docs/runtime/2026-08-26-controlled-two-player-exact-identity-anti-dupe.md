# Controlled two-player exact-identity anti-dupe

Ngày: 2026-08-26

## Kết luận

`VERIFIED CONTROLLED PAPER` cho scope hẹp: một exact non-stackable identity đồng thời nằm trong inventory của hai player online có UUID khác nhau được production scheduled scanner ghi vào cùng completed epoch và tạo đúng một `CONFIRMED/NOTIFY` finding.

Không suy rộng evidence sang multi-server/multi-writer, offline player, double chest, external storage, destructive action, scale hoặc production.

## Artifact và setup

- Paper `1.21.11`, Java 21, clone `E:\AI.WORK\itemguard-paper-smoke`.
- Runtime candidate SHA-256: `89ff7410dc937b29857938a06afac229070a04f5e524bc28d135be20fa28656c`.
- Probe SHA-256: `3186091a8c420453996772295a942f6eb9bd4ac5e89e20b106af14a2b19e78e7`.
- Run token: `af9af793-1bac-4261-b266-9535ad7e3120`.
- Clone-only config trong run: `anti-dupe.enabled=true`, `action=NOTIFY`, scan interval `100` ticks, notification off; `reclaim.issuance-enabled=false`.
- Sau run, clone đã trả `anti-dupe.enabled=false`, scan interval `600`, notification mặc định.
- Production server không bị chạm.

Pre-runtime backup:

- `E:\AI.WORK\backups\itemguard-paper-smoke-two-player-pre-runtime-20260826-152250.tar.gz`
- SHA-256 `e281872ac77797a788be2c03e86767e847784d3ed2e92c8385b51a3930cce954`.

## Fixture

Hai offline-auth Mineflayer players:

- source `IGTwoAlpha`, UUID `97f74c13-2bfd-3ad2-92a8-447ae1c3d496`;
- target `IGTwoBeta`, UUID `d2bdfd27-9153-3c6d-a11d-f66a0da9034c`.

Production `requestPlayerSlotTag` publish một `DIAMOND_SWORD` tại source slot 12. Probe đợi production readiness rồi clone exact serialized bytes một lần sang target slot 12 và gọi `saveData()` cho cả hai. Probe không gọi scanner, finalizer, repository hoặc finding API.

Exact identity:

- code `98IA43`;
- item UUID `ad82304e-3eb6-4110-ae46-821642c2164b`;
- serialized SHA-256 `225860d39384cc70dc2c854fe8e5e4a6b3438a59643c8a3b04dbce437aeae21b`;
- snapshot/publication payload `226` bytes, cùng SHA.

## Baseline và atomic capture

Baseline trước fresh run:

- tracked/snapshot/publication/PUBLISHED `1018/1018/860/851`;
- PREPARED/ABORTED `0/9`;
- history `10`, max history ID `10`;
- findings/stats `2/2`, max finding ID `2`;
- active claims/triggers `0/0`;
- integrity `ok`.

Read-only SQLite watcher pin baseline IDs/epoch và chụp finding + observations trong một transaction trước old-epoch retention.

Winning epoch `1787733435319`:

- finding ID `3`;
- `CONFIRMED/NOTIFY`;
- `distinct_locations=2`;
- exact same code/item UUID;
- đúng một finding trong epoch;
- observation 1: `PLAYER`, source UUID, slot 12, complete=1;
- observation 2: `PLAYER`, target UUID, slot 12, complete=1;
- hai holder UUID khác nhau.

`distinct_locations` trong schema hiện là số physical observation keys, không phải `COUNT(DISTINCT holder_id)`. Với journey này có đúng hai keys `(holder UUID, slot 12)` khác nhau nên value `2` đúng scope; không suy rộng tên cột thành số holder độc nhất trong mọi topology.

Fresh delta:

- tracked/snapshot/publication/PUBLISHED `+1/+1/+1/+1`;
- đúng một attributable history `SPAWN` cho source player;
- finding/stats `+1/+1`;
- PREPARED/ABORTED/claim/trigger delta `0`;
- canonical/snapshot/publication code/UUID/SHA khớp physical item.

## Restart và cooldown

Clean restart giữ hai physical copies trong exact playerdata slots. RAM readiness cache bắt đầu trống; production scan reconcile async rồi scan sau observe. Sau chờ 12 giây, exact restart marker PASS cho hai code/UUID/SHA.

Qua nhiều scheduled epochs và restart:

- findings giữ `3`;
- `duplicates_detected` giữ `3`;
- cooldown không tạo finding/stat mới;
- latest completed epoch vẫn có đúng hai observations của hai holder UUID.

## Cleanup

Probe remove đúng hai fixtures, update inventory và `saveData()` cả hai. Hai bot giữ online qua epoch trống:

- cleanup marker `removed=2`;
- current observations `2 -> 0`;
- finding/stat/canonical/snapshot/publication giữ nguyên durable audit state;
- active claims/triggers `0/0`;
- integrity `ok`;
- clean stop có `Database connection closed` và `All dimensions are saved`.

## Attempts bị loại

### `e9d0414b...`

`HARNESS_ONLY_ORACLE_MISMATCH`. Product đã tạo finding đúng, nhưng watcher sai khi yêu cầu history delta `0`; production first-publication đúng contract tạo một `SPAWN`. Bot chờ marker 180 giây nên winning observation epoch bị retention xóa. Exact two fixtures được cleanup trước fresh run. Archive:

- `E:\AI.WORK\backups\two-player-harness-only-e9d0414b-20260826-153601.tar.gz`
- SHA-256 `427521f51d946860fea9c031f69e7eb3e17331b1da5161123b02470635eb011d`.

### First restart attempt của `af9af793...`

`HARNESS_ONLY_PRE_RECONCILIATION_TIMING`. Bot gọi verify ngay sau join trước production async reconciliation nên nhận `identity-not-ready`. Physical bytes và DB state còn nguyên; production sau đó ghi hai completed observations, cooldown không tăng finding/stat. Retry cùng identity sau hai scan cycles PASS. Archive:

- `E:\AI.WORK\backups\two-player-restart-harness-only-af9af793-20260826-154137.tar.gz`
- SHA-256 `4adb8ab6c654eff33d1a77525d22457da579012174859ee5aa6b5a14397766fa`.

## Verification và review

- Structural fixture contract GREEN.
- Probe Maven package PASS.
- Final Java 21 clean build: 60 suites / `212/212`, zero failures/errors/skips.
- Runtime whole-JAR SHA `89ff7410...`; rebuilt whole-JAR SHA `2583fbd9...`. Reproducible manifest trong evidence chứng minh `332/332` non-manifest entries byte-identical, zero differing entries.
- Pre-runtime exact `cc/claude-opus-4-8`: `PASS`, blockers none.
- Final exact `cc/claude-opus-4-8`: `PASS`, required fixes none, production change `NO`.

Final archive:

- `E:\AI.WORK\backups\two-player-af9af793-20260826-154443.tar.gz`
- SHA-256 `d041a434a66a9cec58153133f7a41482fe977e00629c52b21d54fccc654808b9`.
- Entry manifest SHA-256 `d0b2296033277748ce272caf8b0ec486ef167e104b8e8b75722e257bf6e2a81d`.

## Residual limits

- Multi-server/multi-writer.
- Offline player duplicate/disconnect tại winning epoch.
- Double chest, external storage và non-player holder topology khác.
- Destructive quarantine/removal.
- Scale/soak và production.

Overall project vẫn `NOT RELEASE READY`.
