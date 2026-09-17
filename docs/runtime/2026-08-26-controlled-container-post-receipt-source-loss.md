# Controlled container post-receipt source-loss

Ngày: 2026-08-26

## Kết luận

`VERIFIED controlled Paper` cho scope hẹp một isolated single chest, loaded chunk, slot 3, sau durable reconciliation receipt.

Exact item `58AC7W / d5df7e1c-57be-46c0-9f15-f5aa576039b1` được giữ `PREPARED` với canonical/snapshot `0/0`. Sau restart, production container reconciliation mint exact receipt. Clone-only watcher revalidate block/slot/PDC/serialized SHA rồi xóa slot trên Paper main thread trong exact delayed SQLite reconciliation window. Fresh `BlockState` sau `world.save()` và offline full-world NBT đều xác nhận slot trống. Same publication vẫn commit `PUBLISHED`, canonical/snapshot giữ exact 235-byte SHA, zero post-removal observation; restart tiếp theo vẫn absent.

Đây là point-in-time receipt semantics, không phải atomic Bukkit+SQLite guarantee và không chứng minh source tồn tại tại commit. Không phát hiện production defect; production source không đổi. Project vẫn `NOT RELEASE READY`.

## Artifact và exact oracle

- ItemGuard candidate SHA-256: `189022b7b0e954d5e830896cf7ead09419fade85895d412073230df2d3767e10`.
- Clone-only probe SHA-256: `6bbce8ea0a66787c378b81ca270196fb56b2f4ec13acd18f1df93a32d0a3569e`.
- Run token: `92cfd6f5-844e-477c-9251-3127aede0a04`.
- World UUID: `34486cdf-24f0-4ade-9f92-f820b9605e20`.
- Exact chest: `-3,-60,10`, isolated, slot `3`.
- Source key: `BLOCK_CONTAINER_SLOT:34486cdf-24f0-4ade-9f92-f820b9605e20:-3:-60:10:3`.
- Publication: `e4b5aa86-d84e-4afa-b491-4b33e53e8373`.
- Serialized payload: `235` bytes.
- Serialized SHA-256: `7ae923084128fb43d023a77faa5d2176a1e2b26163c9cc4c4c8b52f8dd607151`.

## Phase 1 — durable PREPARED

Clone offline trước run:

- DB schema `7`, integrity `ok`;
- tracked/snapshot/publication `512/512/354`;
- `PREPARED=0`, active claims `0`, trigger `0`;
- controlled ports `57485/36104` đóng;
- candidate/probe hashes exact.

Exact hold-first trigger chỉ match:

- transition `PREPARED -> PUBLISHED`;
- source key loại `BLOCK_CONTAINER_SLOT`;
- exact fixture item name có run token.

Prepare journey tạo đúng một exact publication rồi trigger abort lần publish đầu. Offline seal:

- same publication `PREPARED`;
- exact canonical/snapshot `0/0`;
- publication/physical SHA và payload length exact;
- totals `512/512/355`, đúng một `PREPARED`;
- DB close, dimensions saved, ports closed, session lock exclusive.

## Phase 2 — source mất sau receipt

Hold-first trigger được thay offline bằng exact `AFTER UPDATE PREPARED -> PUBLISHED` trigger chỉ match publication ID `e4b5aa86-...`. Trigger chạy recursive 30M trước statement return/commit.

Arm journey:

1. Bot mở exact chest. `InventoryOpenEvent -> scanContainerInventory` không first-tag vì item đã có complete identity.
2. Fixture đăng watcher trước khi gọi production `scanOpenBlockContainerInventory`.
3. Production `reconcilePhysicalIdentity` tạo exact `TagReconciliationReceipt`; one serial SQLite owner bắt đầu reconciliation.
4. Watcher chỉ hành động khi exact block/slot, code, item UUID và serialized SHA cùng match và rollback journal active.
5. Fixture xóa slot, gọi `world.save()`, resolve fresh `BlockState`, xác nhận slot empty và journal vẫn active.

Exact marker:

- `journalObservedAt=1787682868723`;
- `removedAt=1787682868724`;
- `journalBeforeRemoval=true`;
- `journalAfterSave=true`;
- `emptyAfterSave=true`.

Offline phase-2 seal sau DB close/dimensions save/ports close:

- same publication `PUBLISHED`, detail `null`;
- exact canonical và snapshot, cùng 235-byte SHA;
- post-removal observations `0`;
- totals tracked/snapshot/publication `513/513/355`;
- `PREPARED=0`, active claims `0`, integrity `ok`;
- exact delay trigger còn tồn tại để attribution được seal trước khi gỡ.

## Offline physical oracle và restart

Full-world region/entity NBT verifier dùng Python 3.11 parse `1,347` chunks:

- exact target là `minecraft:chest`;
- bốn horizontal neighbors đều `minecraft:air`;
- chest có `itemEntries=0`, exact `slotEntries=0`;
- exact code/UUID hits toàn world `0/0`;
- không có component storage không được verifier hiểu.

Sau khi gỡ trigger offline, restart bot gọi exact verify và nhận:

`ITEMGUARD_CONTAINER_POST_RECEIPT_ABSENT_AFTER_COMMIT 58AC7W d5df7e1c-57be-46c0-9f15-f5aa576039b1`

Restart kết thúc với bot PASS, ItemGuard `Database connection closed`, `All dimensions are saved`, ports đóng. NBT verifier sau restart vẫn PASS.

Final DB:

- schema `7`, integrity `ok`;
- tracked/snapshot/publication `513/513/355`;
- `PREPARED=0`, active claims `0`, triggers `0`;
- exact post-removal observations `0`.

## Verification

Focused Java 21:

```text
./mvnw.cmd -Dtest=IdentityReadinessCoordinatorTest,TagPublicationRepositoryTest test --no-transfer-progress
```

- `15/15` PASS.

Full Java 21:

```text
./mvnw.cmd clean test package --no-transfer-progress
```

- `58` suites, `201/201` tests;
- failures/errors/skips `0/0/0`;
- `BUILD SUCCESS`.

Clean build SHA `dff97211184748b7f270e86d5f8cd50cb5956302c8a75b4fe7e4b48738f86ac8` và runtime candidate có `400/400` non-manifest entries byte-identical. `target/ItemGuard-1.0.0.jar` đã khôi phục exact runtime bytes `189022b7…7e10`. `git diff --check` PASS.

## Independent review

Reviewer exact `cc/claude-opus-4-8` trả `VERDICT: PASS`, `PRODUCTION CHANGE REQUIRED: NO`.

Reviewer xác nhận:

- `updated_at` là application-supplied UPDATE time, không phải commit time;
- one serial owner + no-journal-at-arm + exact-only long trigger + exact PDC/SHA revalidation đủ attribution cho trial này;
- `InventoryOpenEvent` không bypass receipt path;
- fresh `BlockState` và offline NBT loại stale-state oracle;
- restart proof độc lập giữ source absent.

Gaps giữ nguyên: journal-to-publication binding là causal inference, marker boolean là self-report sau runtime throw guards, không có negative-control attribution run.

Review: `docs/reviews/2026-08-26-container-post-receipt-source-loss-opus-review.md`.

## Attempts bị loại

Attempt đầu sau deploy probe bị dừng trước fixture vì orchestrator thiếu required `itemguard.entitySourceLossRunToken`, khiến clone-only probe disable. ItemGuard production vẫn enable; DB/publications không đổi và hold-first trigger giữ nguyên. Sau khi bổ sung đủ clone-only JVM properties, prepare/run/restart đều PASS. Attempt này không được dùng làm runtime evidence.

## Evidence

- Final evidence: `E:\AI.WORK\itemguard-paper-smoke\container-post-receipt-final-evidence.json`.
- Final evidence SHA-256: `a16e66e7c6e9414c72d9e8e28f7f12d59454621af90b3da3f36b14685d174c23`.
- Review result SHA-256: `51caa4d2bd3eb5ca6ea162ab3f6d9e7fa0ae7946ebcd6b166d6b1538dbc6d2e8`.
- Evidence archive: `E:\AI.WORK\backups\itemguard-container-post-receipt-source-loss-92cfd6f5-20260826-014053.tar.gz`.
- Archive SHA-256: `3537254cfdfe8377fa5240a75bbe31e4cd2849fa0a214d97f90d6166321141e3`.
- Pre-runtime backup: `E:\AI.WORK\backups\itemguard-paper-smoke-container-post-receipt-pre-runtime-20260826-012514.tar.gz`.
- Pre-runtime backup SHA-256: `cb285b59879d9d0844bdb6ec07fde2f3bf3fb934e40b90eb1235362c1852b9e0`.

## Không được claim

Gate này không chứng minh:

- double chest, non-chest, hopper/dropper/piston;
- unloaded hoặc concurrent container;
- relocation recovery;
- stackable/merge/split identity;
- repeated timing/concurrency/backpressure/scale;
- external absence, destructive quarantine hoặc issuance;
- production behavior hoặc release readiness.

Production source/JAR/server không bị sửa, deploy hoặc restart. Clone-only trigger/probe không được copy sang production.
