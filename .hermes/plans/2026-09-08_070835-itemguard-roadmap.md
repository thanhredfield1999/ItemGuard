# ItemGuard Roadmap Implementation Plan

**Goal:** Đóng các lỗ hổng bằng chứng hiện tại, đưa ItemGuard tới bản tracking/notify có phạm vi hỗ trợ rõ ràng, rồi hoàn thiện reclaim/quarantine/trade theo gate riêng.

**Architecture:** Giữ UUID/PDC + publication journal + SQLite single-owner + bounded observations. Tách hạ tầng controlled-runtime khỏi sản phẩm; không coi lỗi harness là lỗi ItemGuard. Mọi hành động cấp/tịch thu vật phẩm phải có transaction, idempotency và crash recovery riêng.

**Tech Stack:** Java 21, Paper API 1.21.11, Maven, SQLite; successor execution-authority C#/.NET theo thiết kế đã được duyệt offline.

## Phạm vi kiểm tra của phiên này

Read-only Git, product requirements, CURRENT_STATE, checkpoint, risk register, successor design, forensic receipt, manifest/config, source truy xuất qua codegraph, parser và test inventory. Không chạy build/test/Paper, không sửa product, không review lại toàn bộ từng dòng code hay rehash toàn bộ historical runtime archives. Những PASS lịch sử dưới đây là evidence được tài liệu ghi nhận, không phải verification mới trong phiên.

Git hiện tại: main, HEAD `1b7d05517f2d95d979a202971a67a08c54ad153d`, nhiều tracked edits và untracked implementation/tests/docs. HEAD riêng không mô tả đầy đủ candidate. Không reset/clean/commit/push.

## Kết luận baseline

- NOT RELEASE READY. `pom.xml`/`plugin.yml` mang 1.0.0 không có nghĩa đã đủ điều kiện phát hành.
- CURRENT_STATE ghi candidate `2e1fb7d8717a02b316bef9c017d09d67b8705c694519433276bd6e316d5adc5e`, full Java 292/292, controlled publication/restart/source-loss, SQLite single-owner, block-hopper/double-chest, multi-copy NOTIFY, stackable/craft fail-closed và command/GUI scopes.
- Các gate có candidate khác nhau: phải map exact artifact/source delta, không cộng mọi PASS thành một current-candidate PASS.
- `config.yml:113-118` vẫn `anti-dupe.enabled: false`, action NOTIFY. Destructive và issuance chưa mở. SOS mới chứng minh denial; TAKE chỉ persisted intent.
- Thiết kế mới hơn checkpoint: `docs/design/2026-09-01-natural-break-successor-35-execution-authority.md:5-20` đã có user approval OFFLINE DESIGN/TDD, đang blocked pending fresh independent design review. Bundle successor là `review-bundle-attempt-35`, runtime dự kiến `attempt-15`, KHÔNG phải attempt-35. Chưa có sealed successor/review PASS/runtime authorization theo thiết kế.
- Natural break đã tới content entity-add nhưng fresh-identity tail INCONCLUSIVE. Attempt 14 execution NOT_ESTABLISHED vì bundle integrity loss; không kết luận defect ItemGuard hay Paper chưa chạy.

## Roadmap theo dependency

### P0 — Khép blocker hiện tại, không mở thêm chức năng nguy hiểm

1. Đối chiếu checkpoint với successor design và lấy review độc lập Opus 5 theo preference hiện hành, chỉ review scope guardian đang được duyệt; không tự đổi kiến trúc đã được user chọn.
2. Hoàn thiện offline TDD execution authority theo 18 slices ở successor design: journal mirrors/durability/classification, worker containment, strict typed protocol, exact Java/Node compatibility, spawn ownership, stdout drain/output seals, recovery và interruption matrix. Chạy benign fixtures trước, không launch Paper. Không bypass/whitelist/restore AV quarantine.
3. Đóng telemetry natural-break: ItemListener invocation → deferred stable entity → requestEntityTag outcome → publication state/detail → bounded entity/PDC → stopped DB/NBT. Failure phải định vị được seam; timeout đơn lẻ không được gọi là product bug.
4. Seal exact bundle/guardian/recovery + review receipt; xin approval RIÊNG cho namespace mới trước controlled Paper. Attempt 12/13/14 và receipt cũ consumed, không reuse.
5. Chạy natural-break full journey tới identity/publication, restart, cleanup và seal evidence. Nếu quan sát RED sản phẩm, mới viết regression và sửa nhỏ nhất rồi review/test lại artifact mới.

Files tham chiếu/khả năng đổi sau khi được phép triển khai:
- `docs/design/2026-09-01-natural-break-successor-35-execution-authority.md`
- `src/main/java/com/itemguard/listeners/ItemListener.java`
- `src/main/java/com/itemguard/services/ItemTrackingService.java`
- `src/test/java/com/itemguard/listeners/ItemListenerEventContractTest.java`
- `src/test/java/com/itemguard/tracking/EntityPublicationLifecyclePolicyTest.java`
- `src/test/java/com/itemguard/tracking/AsyncTagPublicationCoordinatorTest.java`

Exit: natural break có verdict sản phẩm xác định, đúng exact artifact; không còn kết luận dựa vào thiếu receipt. Guardian hoàn thành chỉ là gate test infrastructure, KHÔNG là release sản phẩm.

### P1 — Chốt phạm vi bản đầu và correctness còn mở

Đề xuất bản đầu giới hạn non-stackable + single-server/local SQLite + topology đã có bằng chứng + tracking/history/search/NOTIFY. Cần user chốt đây là MVP, không tự coi yêu cầu đầy đủ đã bị hủy.

- Fix IG-R014: chốt public code format; generator thực 6 ký tự đang lệch config/test `XXXX-XXXX`. `CodeGenerationTest` chỉ test literal, không gọi generator. Thêm test generator thật, collision cưỡng bức tại unique constraint, bounded retry, exhaustion fail-closed và concurrency; không đổi UUID canonical hay rewrite ID cũ.
- Journey hotbar/storage/armor/offhand, pickup/drop/death/disconnect/rejoin; GUI rapid quit/reopen và late async callback; reload-safe/restart-required.
- Natural place/physics, non-chest, unopened/custom inventories, hơn hai hopper và density: mỗi topology hoặc có runtime evidence, hoặc ghi unsupported/fail-closed và tác động gameplay rõ ràng. Không mở blanket support.
- Relocation và destruction/tombstone: định nghĩa sự kiện nào đủ chứng minh, không suy absence từ unloaded/unobserved; broad crash/unload concurrency có test độc lập.
- Craft hiện eligible output bị cancel: cần công khai hạn chế hoặc làm transaction craft ở phase tính năng; cancellation PASS không đồng nghĩa crafting sử dụng bình thường.

Test targets có sẵn:
`src/test/java/com/itemguard/CodeGenerationTest.java`, `tracking/InventoryPhysicalSourcePolicyTest.java`, `tracking/IdentityReadinessCoordinatorTest.java`, `tracking/HopperTransferPolicyTest.java`, `persistence/TagPublicationRelocationRepositoryTest.java`, `gui/GuiSessionLifecycleWiringContractTest.java`, `config/ReloadPolicyTest.java` (các path rút gọn cùng gốc src/test/java/com/itemguard/).

Exit: support matrix không còn mơ hồ, không silent-loss/false-positive trên journey được hỗ trợ; TDD behavioral, không chỉ source-string wiring test.

### P2 — Hiệu năng, vận hành và tracking/notify candidate

- Chốt tải mục tiêu và budget p50/p95/max, queue/backlog, CPU/heap/DB growth, retention, notification cooldown và shutdown drain.
- Requirement tagging <=1 giây (`PRODUCT_REQUIREMENTS.md:12`) chưa được chứng minh ở mọi tải: historical 100-holder PDC ready 1437.969 ms trong CURRENT_STATE:62. Đo lại theo định nghĩa/tải đã chốt; tối ưu hoặc xin duyệt sửa SLO, không che bằng average.
- Stress mixed player/container/hopper và concurrent crash/unload; regression exact current JAR sau mọi code change.
- Backup/restore + schema migration/corrupt/future rejection; package dependency/native SQLite check; chỉnh config/comment/manifest không quảng cáo phần chưa hỗ trợ.
- BotChecker chỉ dùng version/contract đã chốt sau khi recheck repo đó, không mặc định candidate lịch sử đã release. Ghi report thiếu capability cho BotChecker; bot không thay visual acceptance của user.

Exit: performance đạt SLO, full gate + independent release review + exact artifact checksums + config/migration/operator docs; sau đó mới xin controlled pilot/production riêng. Bản tracking-only không được giới thiệu là hoàn chỉnh reclaim/trade.

### P3 — Hoàn thiện admin investigation, không phát vật phẩm

Source `FindItemCommandParser.java:17-33` mới nhận startfinding/starttaking/stopfinding/listfinding/removefinding/clearfinding. Product requirements còn:
- `readfinding`, `readdupe`: durable acknowledgement + chống spam.
- `checktps`: queue/budget/duration metrics thực.
- `infoplayer`, `infoitem`, `infodupe`: bounded query/evidence/history/audit.
- GUI case/evidence/ack, permission riêng và audit admin; học workflow điều tra của CoreProtect, không copy mù.
- Kiểm tra coverage history create/pickup/drop/use/craft/transfer/container/death và retention mặc định 20 ngày; không tuyên bố event nào chưa có evidence.
- PlaceholderAPI: quyết định namespace/context item chính xác, không tự hứa global `%id%`.

Exit: điều tra end-to-end, async query/main-thread UI, bounded pagination, permission + audit tests và user xác nhận nhìn.

### P4 — External absence và reclaim thực sự

Phụ thuộc P1 correctness + schema/recovery. Có thể nghiên cứu API song song P0, nhưng không mở issuance.

1. Re-audit đúng version PlayerVaults/PlayerVaultsX và zAuctionHouse; cần bounded, side-effect-free lookup trên mọi relevant storage với thread/read-consistency contract. Không có contract thì UNAVAILABLE → DENY; đó là blocker thật, không workaround ABSENT.
2. TDD timeout/error/concurrent modification/offline owners; runtime lookup exact plugin versions. Negative absence phải bao phủ mọi capability được yêu cầu.
3. Thiết kế journal cấp item, cooldown/quota/approval/idempotency, concurrent request, full inventory, disconnect, crash tại từng boundary giữa DB và Bukkit; reconcile inventory receipt sau restart.
4. Mở `/matdo sos` cấp đồ chỉ sau exact evidence. `giveoldid` chịu cùng absence gate; `givenewid` là admin issuance riêng có identity mới và audit, không lách safety.
5. Hoàn thiện `addbackitem/removebackitem/showbackitem` theo contract eligibility/quota đã chốt.

Files: `src/main/java/com/itemguard/reclaim/`, `src/main/java/com/itemguard/persistence/`; tests hiện có `ReclaimCapabilityGateTest`, `ExternalPresenceProbeFactoryTest`, `ReclaimClaimRepositoryTest`, `ReclaimClaimServiceTest`, `ReclaimPreparationServiceTest`, `ReclaimPreflightServiceTest`.

Exit: hai request concurrent tối đa một grant; crash/restart không dupe/mất grant; unavailable tuyệt đối không cấp. Nếu upstream không đáp ứng, hoãn phase này và công khai SOS denial-only.

### P5 — Quarantine/tịch thu/hoàn trả, sau cùng mới cân nhắc xóa/phạt

- Revalidate exact simultaneous physical copies; quarantine có custody/snapshot/actor/reason/evidence/result bền vững.
- Nối TAKE intent tới transaction thực, `givefoundid` trả lại custody đúng một lần; stale slot/container/entity phải abort, không lấy nhầm item.
- TDD crash/partial transfer/inventory full/admin double-click/concurrent holder movement/restart; quarantine reversible trước destructive.
- Automatic delete/punishment giữ OFF cho tới approval + evidence riêng, không bật chung với NOTIFY.

Exit: custody chain có thể kiểm toán và recovery idempotent; các supported source adapters có physical evidence.

### P6 — Trade-only/restrictions và phiên bản đầy đủ

- Chốt trade plugin hoặc native trade transaction; chỉ sau có đường chuyển hợp lệ mới bật chặn drop/chest.
- Matrix death, hopper, shulker, ender chest, anvil/smithing/stonecutter/craft, mailbox, admin bypass, custom inventory/plugin mutations.
- Stackable split/merge/count là milestone riêng, không đổi `track-stackable=true` để tuyên bố hỗ trợ. Minecart/virtual persistence, Folia/multi-server/shared filesystem cũng là scope riêng nếu user cần.
- Full cross-feature acceptance, support docs, migration/rollback, independent review, exact release artifact và visual UAT. Production cần duyệt rõ, không auto-deploy.

Exit: các yêu cầu gốc đã được thực hiện hoặc được user chấp nhận hoãn bằng quyết định rõ ràng; không gắn nhãn complete bằng một bộ unit test xanh.

## Verification workflow khi triển khai (không chạy ở phiên roadmap)

- Java 21; Windows bash gọi Maven wrapper qua cmd: `cmd.exe /d /s /c "mvnw.cmd test package --no-transfer-progress"` trong `E:/AI.WORK/ItemGuard`, sau khi xác minh JAVA_HOME. Không dùng clean mặc định để tránh phá exact artifacts lịch sử.
- Focused test `-Dtest=<actual test class>` RED rồi GREEN trước full gate; giữ output và exact candidate SHA-256.
- `git diff --check`; independent Opus review cho persistence/anti-dupe/release; implementer tự rerun, không dựa self-report.
- Controlled fixture mới phải có exact approval; start/stop bounded, evidence postcondition DB/NBT, ownership/ports cleanup; không production.

## Bước kế tiếp duy nhất được đề xuất

Review độc lập fresh cho successor-35 design rồi tiếp tục offline TDD đã được duyệt. Sau khi hạ tầng test đạt gate, đóng natural-break publication tail. Không quay lại chạy attempt cũ, không bắt đầu SOS cấp đồ, không mở destructive mode.

## Nguồn chính

- `CURRENT_STATE.md:5-7,94-132`
- `docs/RISK_REGISTER.md:7-22`
- `docs/PRODUCT_REQUIREMENTS.md:10-99`
- `docs/design/2026-09-01-natural-break-successor-35-execution-authority.md:5-20,829-957`
- `docs/evidence/2026-09-01-natural-break-attempt-14-forensic-classification.json:59-89`
- `src/main/resources/config.yml:102-118`
- `src/main/java/com/itemguard/commands/FindItemCommandParser.java:17-33`
- `src/test/java/com/itemguard/CodeGenerationTest.java:9-21`
