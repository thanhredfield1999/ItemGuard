# Controlled Paper — double-hopper same-tick concurrency

Ngày: 2026-08-28

## Kết luận

`VERIFIED` cho hai block hoppers cạnh tranh cùng một loaded double chest trong cùng Bukkit tick trên exact candidate:

- ItemGuard JAR SHA-256: `fea0b09cd6dbfffabcdca1a057cee352317535f8f52f982cdf00c00a4846b15e`;
- probe JAR SHA-256: `9d06e0d9c205e91196f0d959c5538e95e65a250efc857923c86b6380df441e36`;
- Paper: `1.21.11-131-ver/1.21.11@6d5b910`;
- authoritative token: `3fdcf034-b8da-4e0e-8813-db569e9e6496`;
- authoritative attempt: `attempt-2`.

Overall ItemGuard vẫn `NOT RELEASE READY`. Production không được truy cập, deploy hoặc restart.

## Scope

Matrix chỉ chứng minh:

1. hai block hoppers khác nhau cùng phát real `InventoryMoveItemEvent` từ một logical 54-slot `DoubleChestInventory` trong cùng Bukkit tick;
2. fresh eligible items tại hai physical chest halves/local slot `0` đều fail-closed trước publication;
3. một hoặc nhiều bounded source scans publish hai identities theo hai exact physical source keys khác nhau;
4. hai tagged retries sau readiness đều được phép, mỗi hopper nhận đúng một distinct identity;
5. exact identity/SHA và stopped DB state giữ qua clean restart;
6. cleanup và operational-allowlist restore chính xác.

Không chứng minh chunk unload mid-transfer, hơn hai initiators, hopper density/performance, Folia/regionized threading, multi-server/multi-writer hoặc production.

## Source và test evidence

`OBSERVED`:

- double-chest scan duyệt logical 54 slots nhưng `BlockContainerPhysicalSlotResolver` quy mỗi raw slot về physical side inventory + local slot;
- `AsyncTagPublicationCoordinator` single-flight theo exact physical source key;
- inventory publication revalidate physical `CraftItemStack` handle và digest trước write;
- readiness chỉ được đánh dấu sau canonical `PUBLISHED`.

Không có production defect mới được xác nhận và không có production behavior change trong slice này.

Characterization test mới khóa hai distinct source keys có thể reserve/publish độc lập khi completion đảo thứ tự. Test PASS ngay từ lần đầu, vì vậy không được gọi là RED/fix.

Verification:

- focused: `23/23` PASS;
- full Java 21 clean test/package: `289/289` PASS;
- probe build/static contract: PASS;
- `git diff --check`: PASS.

Whole-JAR hash khác candidate topology trước do clean ZIP/manifest metadata; comparison độc lập xác nhận `420/420` non-manifest entries byte-identical, `0` differing entries.

## Independent review

Exact Claude Opus 4.8 read-only review:

- verdict: `PASS_FOR_CONTROLLED_PAPER`;
- blockers: `[]`;
- prompt SHA-256: `41d981dcd8a44df0c5c370db055a3f000493ae79e763d9cb1bd8ae1b10ae04c9`.

Reviewer yêu cầu same-tick witness, exact event telemetry, independent stopped-SQLite attribution, restart, cleanup/config/restore checks. Tất cả được kiểm trong authoritative attempt.

## Authoritative controlled evidence

### Same-tick witness

`VERIFIED` ở Bukkit tick `48`:

- hopper one untagged attempts/cancelled/allowed: `1/1/0`;
- hopper two untagged attempts/cancelled/allowed: `1/1/0`;
- hai initiator inventories là hai distinct physical block hoppers;
- source double chest chưa có fixture identity khi hai attempts được ghi;
- tagged ALLOW sau readiness: `1` cho mỗi hopper.

Hai hoppers đều đọc cùng logical double chest; evidence không giả định hopper chỉ đọc physical half ngay phía trên.

### Physical publication attribution

Fresh physical fixtures:

- item A: `DR5YM0 / 91d28cf9-2ade-4675-9c32-ba1c2ec8a18b`;
- item B: `T122UA / 5eb76b3f-850e-40ad-85c4-c2df40da2af7`.

Stopped SQLite rows được preserve và independent verifier đối chiếu:

- A → `BLOCK_CONTAINER_SLOT:34486cdf-24f0-4ade-9f92-f820b9605e20:15:-56:10:0`;
- B → `BLOCK_CONTAINER_SLOT:34486cdf-24f0-4ade-9f92-f820b9605e20:16:-56:10:0`.

Mỗi code có đúng một:

- canonical `tracked_items` row;
- `item_snapshots` row với exact serialized SHA;
- `tag_publications` row `PUBLISHED`, owner `null`, exact physical source key.

DB delta so với baseline:

- tracked `+2`;
- snapshots `+2`;
- publications/PUBLISHED `+2/+2`;
- PREPARED/ABORTED/history/observations/findings/stats/claims/triggers: zero delta;
- `PRAGMA integrity_check=ok`.

### Restart và cleanup

Clean restart giữ:

- hopper one exact SHA `f95f4f8e33aa1367900645225699054331fc9ffcebeb5ff4dd066e5dc728c9ba`;
- hopper two exact SHA `780b10cf8b5e56911c56e61933ac132c105144c2cf4ac1603d55c9438c5fabf1`;
- exact DB logical SHA `f62f4162e6ff09af25ad5b5186549dd12ddb5475755e2e1c0fe9e60d0eebf403` bất biến qua restart và cleanup.

Cleanup xóa đúng `2` items và `4` blocks. Mỗi phase có clean ItemGuard DB close, world save và Paper exit; ports đóng sau mỗi phase.

## Audit chain

- `attempt-1`: runtime behavior PASS và restore PASS, nhưng non-authoritative vì sealer chỉ assert mà không preserve publication/canonical/snapshot row payload; sau restore không thể independently read back exact rows từ evidence. Classification: `RUNTIME_PASS_BUT_INDEPENDENT_ROW_READBACK_NOT_PRESERVED`.
- `attempt-2`: cùng production/probe/review/fixture; chỉ sửa evidence sealer để serialize các rows đã assert. Runtime PASS và independent verifier `PASS_AUTHORITATIVE_DOUBLE_HOPPER_SAME_TICK`.

Không xóa hoặc relabel attempt 1 thành production failure.

## Restore

Fresh operational backup:

- `E:/AI.WORK/backups/itemguard-double-hopper-pre-runtime-20260828-051713.tar.gz`;
- SHA-256 `062b50dae188cc25c2a2a745bf66961b32b80bb736c7665b25d7fd1675b23033`;
- `191` files read-back verified.

Sau authoritative run:

- baseline ItemGuard/probe/DB byte hashes được restore;
- DB logical SHA trở lại `944413c7d472f2a3af42ef42e998449ba18c0dc080529295de5dd9b13682f3e0`;
- integrity `ok`;
- WAL/SHM/journal/sidecar lock không tồn tại;
- ports `57485/36104/57486` đóng.

Restore proof chỉ áp dụng operational allowlist đã ghi trong manifest, không phải toàn bộ clone/log/build workspace.

## Residual scope

Vẫn `NOT VERIFIED` hoặc unsupported:

- chunk unload trong in-flight hopper publication/transfer;
- hơn hai competing initiators và hopper density/performance scale;
- Folia/regionized threading;
- minecart/entity/virtual persistence (hiện chỉ fail-closed);
- unopened scanning và throwing custom inventory accessors;
- multi-server/multi-writer/shared filesystem;
- production deployment/verification.

`ContainerListener` cooldown map dùng `holder.toString()` và không có eviction là residual memory/stability concern non-blocking cho slice này; exact-source single-flight và identity filtering khiến duplicate scans không phá invariant đã kiểm. Cần xử lý/đo riêng, không coi runtime này là performance proof.

Evidence root: `E:/AI.WORK/evidence/itemguard-double-hopper-concurrency-20260828/`.

External sealed archive receipt (được tạo sau archive, không tự nhúng vào archive):

- archive: `E:/AI.WORK/evidence/itemguard-double-hopper-concurrency-20260828.tar.gz`;
- SHA-256: `81b238a549ae0a45b3703a2decbdb8041a624531d4bde09263eb92fa382e4a25`;
- `73` members (`72` payload files + internal checksum manifest), read-back verified.
