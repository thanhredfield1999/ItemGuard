# ItemGuard — Remaining Roadmap Implementation Plan

**Goal:** Hoàn thiện kho điều tra item và các yêu cầu tracking/anti-dupe/reclaim/trade, không nhầm một GUI offline chạy được với sản phẩm hoàn chỉnh.

**Architecture:** Giữ UUID/PDC + publication journal + SQLite single-owner + bounded observations. Catalog chỉ đọc là một lát cắt của sản phẩm, không phải quyết định bỏ các chức năng còn thiếu. Nguồn tạo item, nơi lưu, người thực hiện, người giữ và quyền sở hữu là các dữ kiện riêng, phải có evidence và thời điểm.

**Tech Stack:** Java 21, Paper API 1.21.11, Maven, SQLite; hạ tầng execution-authority C#/.NET theo thiết kế hiện hữu.

## Phạm vi phiên lập roadmap

Các mô tả baseline trong mục này và “Đã có/chưa có” là snapshot lúc lập kế hoạch, không đại diện candidate sau implementation. Tiến độ mới nhất nằm trong từng C/P và đầu CURRENT_STATE.md.

- Chỉ đọc và lập kế hoạch; không triển khai, build, chạy Paper, migration, commit/push/deploy.
- Git đã kiểm tra: `main`, HEAD `1b7d05517f2d95d979a202971a67a08c54ad153d`, nhiều thay đổi chưa commit. Không dùng HEAD riêng để đại diện candidate.
- Hash source/tests trong manifest catalog và JAR hiện tại vẫn khớp bằng chứng ngày 08/09. 334 tests / 30 catalog là kết quả đã chạy ở phiên implementation, KHÔNG phải test chạy lại hôm nay.
- Không ghi `.hermes/WORKING_STATE.md`: lần ghi trước bị approval timeout. File kế hoạch này là deliverable user vừa yêu cầu, không phải cách ghi thay thế checkpoint bị chặn.
- Bổ sung roadmap gốc `.hermes/plans/2026-09-08_070835-itemguard-roadmap.md`, không xóa/cancel P0–P6 hoặc tự coi MVP đã được user chốt.

## Đã có và chưa có

ĐÃ CÓ OFFLINE:
- Catalog A `/ig browser`, tìm tên/mã/material, người liên quan trong bản ghi, phân loại theo material, phân trang, hồ sơ và tối đa 100 sự kiện còn lưu.
- Query có giới hạn; guard quyền/session/callback/click; review Opus5 phạm vi offline.
- Một số lớp codec, lifecycle, control, journal của hạ tầng test đã có unit/integration offline evidence.
- Generator mới và collision repository/coordinator đã có tests; chưa hoàn thành toàn đường publication.

CHƯA ĐỦ:
- Catalog không phải danh sách mọi item đang tồn tại trên server.
- Chưa nguồn plugin đáng tin, phân loại custom, HavenBags, chuỗi chuyển tay/người cầm gần nhất được xác minh.
- Chưa Paper/client acceptance cho GUI mới; chưa scale đạt yêu cầu; chưa hoàn tất guardian/natural-break/P1 và các chức năng nguy hiểm.
- Trên 1M sự kiện tự sinh, history query hiện bị SQLITE_INTERRUPT; thêm index trong DB scratch đọc đúng 100 rows. Chưa có migration sản phẩm.

## Thứ tự đề xuất

1. C1: chốt thiết kế index/migration, sửa blocker lịch sử lớn bằng TDD offline.
2. P1a: nối generator mới vào service, khép collision/retry/reconciliation đúng artifact.
3. C2–C3: chốt mô hình provenance/custody và contract adapter, triển khai kho nguồn plugin/HavenBags có coverage rõ ràng. Research API có thể song song.
4. P0: hoàn thiện execution-authority và natural-break; phần offline độc lập có thể làm song song nhưng đây vẫn là gate bắt buộc trước runtime bị chặn hiện tại.
5. P2 + C4: kiểm chứng hiệu năng, runtime GUI/tracking và người dùng xác nhận phần nhìn, chỉ sau quyền fixture mới.
6. P3: khép bộ công cụ điều tra/admin/NOTIFY.
7. P4 → P5 → P6: reclaim → quarantine/hoàn trả → trade/restrictions → full release, từng gate riêng.

Không đổi thứ tự safety bằng cách làm GUI trước; không dùng review offline làm runtime receipt.

## C1 — Catalog/history chạy được khi DB lớn (ưu tiên ngay)

Tiến độ 09/09: indexed-history migration v8 + failure-safe paging đã VERIFIED offline (351 tests, 3 synthetic >1M-event DBs, 60 exact history queries, Opus5 implementation + bounded followup, exact JAR). Evidence: `docs/reviews/2026-09-09-catalog-c1-evidence.md`. General contains-text search trên 100k records (không khớp hoặc hiếm/cuối bảng) vẫn SQLITE_INTERRUPT; không đánh dấu toàn C1/performance gate hoàn tất. Live migration/backup-restore/Paper cần approval riêng. P1a vẫn là slice source/offline kế tiếp trong thứ tự đề xuất.

Files hiện có: `src/main/java/com/itemguard/persistence/SqliteSchemaManager.java`, `SqliteConnectionOwner.java`; `src/main/java/com/itemguard/catalog/CatalogRepository.java`, `CatalogReadGate.java`, `CatalogController.java`; `src/test/java/com/itemguard/persistence/SqliteSchemaManagerTest.java`; `src/test/java/com/itemguard/catalog/CatalogRepositoryTest.java`, `CatalogReadGateTest.java`, `CatalogControllerTest.java`; `tools/catalog/CatalogScaleProbe.java`.

- [x] Review thiết kế additive history index/version/rollback/unknown data/writer-retention — bounded OFFLINE; live disk/power/production gates riêng còn mở (C1 evidence).
- [x] Behavioral RED legacy DB migration giữ rows/index/idempotent/future/corrupt/stamp failure.
- [x] GREEN migration tối thiểu; giữ query budget và không unbounded scan trên main thread.
- [x] Real JDBC query plan + synthetic >1M history, exact identity/tie order, fail không giả empty, owner hoạt động — OFFLINE, không production SLO.
- [x] Failure-safe paging: chỉ commit cursor sau success, busy/retry giữ request/page/filter; shared admission không bỏ. Live multi-admin còn C4.
- [ ] Đo filtered search và mixed read/write; một index history không giải quyết mọi scan theo tên/material.

Exit: migration/recovery tests và exact query results PASS; đo scale/queue, không chỉ thời gian một lần thử. Nếu cần đổi SLO hoặc cấu trúc tìm kiếm, trình tradeoff trước khi làm.

## C2 — Nguồn plugin và danh mục vật phẩm

Files hiện có làm điểm tích hợp: `src/main/java/com/itemguard/catalog/`, `src/main/java/com/itemguard/data/ItemData.java`, `src/main/java/com/itemguard/services/ItemTrackingService.java`, `src/main/java/com/itemguard/persistence/ItemSqliteRepository.java`, `src/main/resources/plugin.yml`. Chưa chốt tên lớp/field/schema mới.

- [ ] Chốt contract provenance: plugin nào, version/API nào, evidence/key nào, lúc ghi nhận, độ chắc chắn; UNKNOWN và conflict là trạng thái hợp lệ.
- [ ] Dựa nguồn thực/API đúng version, không suy plugin từ tên/lore hoặc nơi item đang nằm. Không tự gán Vanilla khi thiếu chứng cứ.
- [ ] Phân loại material giữ fallback; adapter custom có nhãn riêng và quy tắc precedence có test.
- [ ] Hỗ trợ item cũ có lộ trình backfill bounded; không scan toàn world/force-load chunk, không sửa identity/PDC ngoài contract.
- [ ] GUI lọc nguồn/loại, hiển thị rõ unknown/conflicting/adapter unavailable.

Exit: fixtures chứa nhiều nguồn, tên/lore giống nhau, plugin thiếu/đổi version, dữ liệu cũ; không false attribution. Danh sách plugin cần hỗ trợ phải được chốt theo server thực, không tự nhận mọi plugin.

## C3 — HavenBags, nơi lưu và chuỗi chuyển tay

Tiến độ 09/09: observation-first browser trên cache hiện hữu VERIFIED OFFLINE, 378 Java tests/52 catalog, Opus5 R2 + bounded followups và exact 218-class JAR. Evidence `docs/reviews/2026-09-09-retained-observations-evidence.md`. Không schema/adapter/writer/scan mới, không durable chain/current holder/full absence. Pinned upstream research đã có; installed HavenBags JAR/server path vẫn thiếu. Các mục toàn C3 bên dưới vẫn mở, không tick quá phạm vi.

- [x] Bounded retained-observation list/detail/back; source/time/epoch/pruning/partial/unknown/error labels, same/cross-epoch không suy simultaneous dupe; real JDBC scale/reopen + controller/UI seam + independent review/JAR OFFLINE.

Điểm tích hợp hiện có: `src/main/java/com/itemguard/catalog/`, `src/main/java/com/itemguard/reclaim/`, `src/main/java/com/itemguard/persistence/`, `docs/research/2026-08-24-external-presence-api-support-matrix.md`, `docs/design/2026-09-08-item-catalog-ux.md`.

- [ ] Audit đúng HavenBags version/API/threading: cách nhận diện túi, enumerate/read nội dung bounded, event mở/đóng, offline storage, túi cho mượn/unbound, lỗi/unloaded.
- [ ] Tách identity chiếc túi, đồ bên trong, chủ túi và người cầm túi. Item trong HavenBags không mặc nhiên do HavenBags tạo.
- [ ] Chốt observation/custody contract: from/to, actor, holder, storage, timestamp, evidence reference; phân biệt latest recorded với current verified. Transfer không đủ chứng cứ phải giữ gap/unknown.
- [ ] History qua người A → túi/rương → người B; duplicate concurrent copies không tự chọn một người cầm duy nhất. Không xem actor sự kiện là người giữ.
- [ ] Retention giữ phần còn lại có nhãn thiếu; dữ liệu cũ không bịa chain. Read thất bại không chứng minh item đã mất.
- [ ] GUI có Nguồn / Qua tay ai / Nơi lưu với back/filter/page dễ dùng; coverage và thời điểm nằm ngay nơi staff ra quyết định.

Exit: adapter tests + controlled đúng version khi được phép; xem được túi/nội dung/người giữ có nguồn chứng minh, offline/unavailable không fake complete. Nếu public API thiếu, ghi blocker cụ thể, không lách bằng ABSENT.

## C4 — GUI thực tế và visual acceptance

Files: `src/main/java/com/itemguard/catalog/CatalogUi.java`, `CatalogInventory.java`, `CatalogController.java`; tests `CatalogUiLifecycleTest`, `CatalogInventoryTest`, `CatalogControllerTest`.

- [ ] Đúng JAR được review; fixture/receipt mới sau P0 gate và user approval riêng.
- [ ] Mở → lọc → paging → hồ sơ → history → detail → back giữ ngữ cảnh; chat tiếng Việt/hủy/timeout, legacy chat bridge thực.
- [ ] Click/shift/number-key/drag/double-click/creative; cursor item trước/sau không mất/nhân bản, preview không trở thành item thật.
- [ ] Hai admin, mất quyền từng node, quit/rejoin, mở GUI plugin khác, callback trễ, shutdown/reload-safe; không auto `/reload` server.
- [ ] User xác nhận font/icon/lore và dễ click trên client; automation không thay visual acceptance.

Exit: runtime evidence đúng artifact và user xác nhận phần nhìn, không chỉ proxy Bukkit/unit.

## P0 — Hạ tầng kiểm chứng + natural-break (vẫn OPEN)

Files: `tools/execution-authority/`; `docs/design/2026-09-01-natural-break-successor-35-execution-authority.md`; `ItemListener.java`, `ItemTrackingService.java` dưới src/main/java/com/itemguard.

- [ ] Hoàn thiện strict payload schemas/trusted genesis/process identity, terminal/history classifier, real dual-durable writer.
- [ ] Win32 pipe/token/job/ACL ownership, deadline/stdio drain, interruption/AV/drift fixtures an toàn; không bypass/quarantine restore.
- [ ] Natural-break tail: listener → deferred entity → request outcome → publication → exact PDC → stopped DB/NBT → restart/cleanup.
- [ ] Exact sealed successor review và approval namespace mới; attempts/receipts cũ consumed, không reuse.

Exit: hạ tầng không suy absence thành never-executed; natural-break có verdict sản phẩm xác định trên đúng artifact. Hạ tầng PASS không tự là release PASS.

## P1 — Identity/publication và correctness (vẫn OPEN)

Files: `src/main/java/com/itemguard/identity/PublicItemCodeGenerator.java`, `src/main/java/com/itemguard/services/ItemTrackingService.java`, `src/main/java/com/itemguard/tracking/AsyncTagPublicationCoordinator.java`; tests `identity/PublicItemCodeGeneratorTest`, `tracking/TagPublicationCollisionRetryTest`, `persistence/TagIdentityCollisionRepositoryTest`.

- [x] P1a generator nối service bằng behavioral TDD, SecureRandom và 6 ký tự thống nhất; không rewrite legacy UUID/code. Final 364 Java tests/Opus5 followup/exact JAR OFFLINE (`docs/reviews/2026-09-09-p1a-generator-service-evidence.md`).
- [x] Collision/retry/exhaustion/concurrent reservations, no physical write before reservation, stale source/generic error, old PREPARED reopen và publish outage/reconcile: 7 public service+real JDBC cases. Bukkit boundary mocked; không claim Paper restart/NBT.
- [ ] Hotbar/armor/offhand/death/disconnect/rejoin, relocation, natural place/physics, loaded/unloaded, container/hopper topology, restart và reload-safe.
- [ ] Chốt support matrix; unsupported/fail-closed phải nói tác động gameplay, đặc biệt craft eligible output hiện bị cancel. Không tự coi cancellation là craft dùng bình thường.

Exit: đúng identity và không silent-loss/false-dupe trong phạm vi được hỗ trợ; source/unit + runtime gate tương ứng, review exact current.

## P2 — Performance, vận hành, tracking/NOTIFY candidate (OPEN)

- [ ] Chốt workload và SLO; tagging ≤1s theo requirement cần đo tail/queue thực, không average để che miss.
- [ ] Mixed inventories/hoppers/catalog/history, p50/p95/max, DB growth/retention, backlog, shutdown drain, long-run memory.
- [ ] Backup/restore, migration/corrupt/future schema, dependency/native SQLite packaging và operator docs.
- [ ] BotChecker version/contract thực tại lúc chạy; những thao tác chưa hỗ trợ cần test bổ sung, không fake evidence.

Exit: scope tracking/NOTIFY được user chấp nhận, load/runtime/release review đúng artifact. Không tự quyết bản tracking-only thay sản phẩm đầy đủ.

## P3 — Điều tra/admin/NOTIFY đầy đủ (OPEN; catalog mới là một phần)

Files: `src/main/java/com/itemguard/commands/FindItemCommandParser.java`, `FindItemCommand.java`, `src/main/java/com/itemguard/search/`, `dupe/`, `catalog/`.

- [ ] `readfinding/readdupe`: acknowledgement bền vững, chống spam, audit.
- [ ] `checktps`: scan queue/budget/duration thật; `infoplayer/infoitem/infodupe`: evidence/history bounded.
- [ ] Case GUI, trạng thái SUSPECTED/CONFIRMED/RESOLVED có revalidation; không dựa history count.
- [ ] Coverage create/pickup/drop/use/craft/transfer/container/death và retention; PlaceholderAPI namespace/context đúng.

Exit: staff điều tra end-to-end được và hiểu dữ liệu thiếu; permission/audit/restart tests, visual/runtime acceptance.

## P4 — Reclaim cấp lại an toàn (OPEN)

Files: `src/main/java/com/itemguard/reclaim/`, `persistence/`, `commands/MatDoCommand.java`.

- [ ] External presence API PlayerVaults/PlayerVaultsX/zAuctionHouse/HavenBags đúng version, bounded và không side effect. UNKNOWN/UNAVAILABLE/timeout ⇒ deny.
- [ ] Journal/idempotency/cooldown/quota/approval và full-fidelity snapshot; hai request concurrent tối đa một grant; full inventory/disconnect/crash/restart từng ranh giới DB/Bukkit.
- [ ] `/matdo check/sos`, `giveoldid/givenewid`, `addbackitem/removebackitem/showbackitem` đúng contract; identity mới không phải cách lách absence gate.

Exit: no duplicate grants và recovery đúng; public API thiếu thì hoãn cấp, công khai SOS denial-only. Reclaim không tự được mở vì catalog hoạt động.

## P5 — Tịch thu/quarantine/hoàn trả (OPEN)

- [ ] Revalidate bản sao physical; TAKE intent → transaction thật có custody/snapshot/actor/reason/evidence/result.
- [ ] Stale slot/move/unload abort không lấy nhầm; partial/crash/full inventory/admin double-click/restart.
- [ ] `givefoundid` hoàn trả đúng một lần; quarantine reversible trước xóa/phạt.

Exit: custody và recovery kiểm toán được; automatic delete/punishment giữ OFF tới approval và evidence riêng.

## P6 — Trade-only, compatibility và full release (OPEN)

- [ ] Chốt native trade hoặc plugin trade hợp lệ trước khi chặn drop/chest.
- [ ] Death/hopper/shulker/ender chest/anvil/smithing/stonecutter/craft/mailbox/admin/custom mutations compatibility.
- [ ] Stackable split/merge/count là milestone riêng; Folia/multi-server/shared FS không tự nhận support.
- [ ] Cross-feature acceptance, migration/rollback/distribution docs, release review, exact checksums, user UAT.

Exit: yêu cầu gốc đã làm hoặc user chủ động duyệt hoãn. Không gọi complete chỉ vì unit/build xanh. Production cần approval rõ, không auto deploy.

## Cách kiểm chứng lúc thực thi

1. Read current source/rules/diff; không reset worktree hoặc tự commit.
2. Mỗi defect/behavior: behavioral RED → sửa tối thiểu → GREEN focused → full gate; coverage green-first ghi đúng tên.
3. Windows bash: `export JAVA_HOME='C:/Program Files/Java/jdk-21'; export PATH="$JAVA_HOME/bin:$PATH"`; `cmd.exe /d /s /c "mvnw.cmd -Dtest=<actual class> test --no-transfer-progress"`.
4. Full candidate theo CHEATSHEET: `cmd.exe /d /s /c "mvnw.cmd clean test package --no-transfer-progress"`, sau khi bảo tồn exact artifact nếu có receipt đang bind target.
5. `git diff --check`, parse Surefire counts, hash scoped source/tests/JAR, independent review; packaged classes phải tương ứng source build.
6. Runtime chỉ sau prerequisite + approval fixture mới; log DB/NBT/physical state, clean stop và verified cleanup trong đúng scope. Không chạm production.

## Nguồn / quyết định còn mở

- Checkpoint triển khai tiếp 09/09: C2/C3 audit pinned source tại `docs/research/2026-09-09-havenbags-custody-contract-audit.md`. Cần duyệt durable observation/provenance/custody contract và server/artifact mục tiêu, không hỏi lại tên HavenBags đã biết. Empty cache/BagCloseEvent không chứng minh absence/durable transfer.
- P0 thêm pure dual-commit core (`docs/design/2026-09-09-dual-journal-commit-core.md`), không real dual-durable writer; payload/trusted genesis + host sink/token/job/ACL/deadline + sealed namespace vẫn OPEN. Không tick completion P0 từ pure tests.

- Roadmap gốc: `.hermes/plans/2026-09-08_070835-itemguard-roadmap.md`.
- `docs/PRODUCT_REQUIREMENTS.md`; `CURRENT_STATE.md:3-8`; `docs/RISK_REGISTER.md`.
- `docs/reviews/2026-09-08-catalog-implementation-evidence.md`; `catalog-final-manifest.json`; `catalog-synthetic-scale-final.log`.
- `docs/reviews/control-implementation-evidence.md:43-47`; `journal-implementation-evidence.md:31-35`.
- Cần duyệt trước thay đổi: migration/provenance/custody contract, plugin/version support và workload SLO; MVP/full-feature phasing; runtime fixture và production authorization là quyết định riêng.
- Bước đầu tiên đề xuất: thiết kế/review index migration C1 trên schema hiện tại, không chạy Paper và không nâng budget để che lỗi.
