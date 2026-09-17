# Controlled stackable identity fail-closed

Ngày: 2026-08-26

## Kết luận

`VERIFIED controlled Paper` cho contract hẹp MVP non-stackable:

- stackable chưa tag không được cấp UUID-per-item identity, kể cả compatibility key `track-stackable=true`;
- real split vẫn diễn ra theo vanilla nhưng các physical outputs đều untagged;
- exact legacy stackable đã `PUBLISHED` bị interaction/readiness/observation fail-closed;
- trạng thái giữ nguyên qua clean restart;
- canonical ledger không bị tự sửa/xóa và DB không tăng.

Production chưa deploy/restart/verify. Project vẫn `NOT RELEASE READY`.

## RED candidate

- Candidate: `189022b7…7e10`.
- Fixture identity: `WOZF78 / 7904f9e8-267a-4502-a6bc-e2369a02bf9e`.
- Publication: `5b0dc861-2937-4eef-84c2-ccb624752812`, state `PUBLISHED`.
- Snapshot: `186` bytes, SHA-256 `6ddf6c0c664c37d46bdd00363a8dc64f78d9022ea08e5ef6c339513b37d5b03a`.
- `APPLE x2` ở player slot 9 được first-tag rồi real right-click split thành slots 9/10, mỗi slot `APPLE x1` cùng exact identity.
- Completed DB epoch có đúng hai observations cùng identity tại slots 9/10.
- Restart giữ cả hai physical copies.

Hai wrapper exit `1` được loại khỏi process-success claim: bot timer referenced và shutdown wait timing. Gameplay markers, exact DB oracle, `Database connection closed` và `All dimensions are saved` đều tồn tại; RED evidence được seal riêng trước cleanup.

## Fixed candidate

- Candidate SHA-256: `89ff7410dc937b29857938a06afac229070a04f5e524bc28d135be20fa28656c`.
- Probe SHA-256: `c905f2205ff9d4d304b50ccc55c590e5866f61ee1d37f67ec4405f216d6780bd`.
- Run token: `9284f975-9ce1-4ece-9436-7473fca03c33`.
- Controlled ports: Minecraft `57485`, query `36104`.
- Clone-only config: `tracking.track-stackable=true`.
- Production server untouched.

## Baseline

Read-only SQLite baseline trước runtime:

- integrity `ok`;
- tracked/snapshots `1017/1017`;
- publications `859`, PREPARED `0`, ABORTED `9`;
- observations `0`, findings `1`, history `9`;
- claims `12`, active claims `0`, triggers `0`;
- exact legacy canonical/snapshot/publication row match code, UUID, publication ID, payload bytes và SHA.

Baseline DB SHA-256: `5567eca14a93e75d5ecddd99f6219e266f5e39e261fa63b30be4d52358559eaa`.

## Journey

### Fresh stackable

1. Fixture đặt fresh `APPLE x2` tại slot 9 và target slot 10 trống.
2. Production `shouldTrack=false`; synchronous tag request trả false; sau bounded ticks vẫn không code/UUID/PDC.
3. Mineflayer gửi real right-click slot 9 rồi slot 10.
4. Client và authoritative server thấy slots 9/10 mỗi slot `APPLE x1`; cursor empty.
5. Cả hai payload hash bằng nhau nhưng không chứa ItemGuard identity; repeat tag requests vẫn false.
6. Manual scan epoch không tạo observation.

### Exact legacy stackable

1. Fixture restore exact old `APPLE x2` từ `DatabaseManager.getSnapshot("WOZF78")`; không dựng PDC thủ công.
2. Type, amount, code, UUID và payload SHA được đối chiếu trước khi đặt vào slot 11.
3. Production `shouldTrack=false`, `isIdentityReady=false`.
4. Mineflayer log `CLICK_SENT legacy slot=11 right`.
5. Sau click, client và authoritative server đều giữ slot 11 `APPLE x2`, cursor empty; marker `LEGACY_BLOCKED_PASS` được ghi.

### Restart và persistence

1. Verify phase shutdown sạch: `Database connection closed`, `All dimensions are saved`, process exit 0.
2. Restart clean bằng cùng candidate/probe/run token.
3. Client và server cùng thấy slots 9/10 vẫn là untagged x1; slot 11 vẫn exact legacy x2, readiness false.
4. Restart shutdown sạch và process exit 0.

### DB seal trước cleanup

- Exact canonical/snapshot/publication legacy bằng baseline, gồm publication ID và payload SHA.
- Tất cả aggregate counts bằng baseline; integrity `ok`.
- Exact manual epoch observations `0`.
- Candidate/probe hashes match baseline.
- Không `REJECTED`, exception hoặc SEVERE trong reviewed logs.

### Cleanup

- Controlled cleanup xóa đúng ba fixture slots.
- Shutdown sạch.
- Post-cleanup SQLite state vẫn bằng baseline và integrity `ok`.

## Verification

- Policy/wiring RED rồi GREEN.
- Focused Java 21: `16/16` PASS.
- Full command: `JAVA_HOME=C:\Program Files\Java\jdk-21 .\mvnw.cmd clean test package --no-transfer-progress`.
- Full result: `60` suites / `212/212` PASS, zero failures/errors.
- `git diff --check` PASS.
- Pre-runtime reviewer: fallback `ag/claude-opus-4-6-thinking`, `PASS`.
- Final reviewer: exact `cc/claude-opus-4-8`, `PASS`, required fixes none.

## Scope limits

Chứng minh cho một player, `APPLE`, một real split, một exact legacy click và một restart. Không chứng minh merge-between-different-stacks, custom/per-item max-stack component behavior, multi-player/writer, runtime hopper, death-drop lifecycle, scale, production deployment hoặc release readiness.

Legacy tagged stackable rơi do death có thể thành ground entity không pickup được; đây là residual availability/UX risk, không phải duplication evidence.

## Evidence

- RED directory: `E:\AI.WORK\itemguard-paper-smoke\evidence\stackable-merge-red-5c2873b4`.
- GREEN directory: `E:\AI.WORK\itemguard-paper-smoke\evidence\stackable-merge-fixed-9284f975`.
- Final seal: `E:\AI.WORK\itemguard-paper-smoke\stackable-merge-fixed-final-evidence.json`.
- Combined archive: `E:\AI.WORK\backups\itemguard-stackable-merge-fixed-9284f975-20260826-134440.tar.gz`.
- Archive SHA-256: `5762d7c6df486709520bcb7992fc52d3f1892228ec4827cc9aff86d0e1676e24`.
