# ItemGuard LITE — Release and Maintenance Implementation Plan

**Goal:** Hoàn thiện LITE English-first, đủ bằng chứng để Thanh đăng SpigotMC, rồi duy trì sản phẩm; không biến guardian thành mục tiêu thay thế plugin.

**Architecture:** Java/Paper là sản phẩm. C# execution-authority là hạ tầng kiểm chứng riêng, không phải dependency khách hàng. Giữ identity/history/GUI/NOTIFY; không restore, teleport, confiscation hoặc issuance trong LITE.

**Tech stack:** Java 21, Maven, Paper 1.21.11, SQLite, Python fixture/verifier; C# chỉ cho host authority khi protocol yêu cầu.

## Quy tắc thực thi

- Một writer trên repo dirty; không reset/xóa thay đổi ngoài phạm vi, không commit/push khi chưa được yêu cầu.
- Tiếp tục liên tục tới checkpoint có evidence hoặc blocker thật; không yêu cầu Thanh duyệt từng thay đổi nhỏ đã nằm trong scope.
- Mỗi lỗi: tái hiện RED → sửa nhỏ nhất → focused GREEN → full gate. Review độc lập cho persistence/security; timeout không phải PASS.
- Đọc Main/CHEATSHEET.md, CURRENT_STATE.md, .hermes/WORKING_STATE.md, docs/RISK_REGISTER.md và tra memory đúng project trước sửa.
- Handoff phải nêu candidate/hash, evidence, việc còn mở, process/port cleanup. Không tự tắt hạ tầng memory.
- Không bỏ gate natural-break, tái dùng fixture consumed, whitelist AV hoặc thay API chỉ để lách quyền. Lỗi Permission denied chưa chứng minh nguyên nhân là AV; cần log hệ thống để kết luận.
- Kế hoạch này không cấp quyền publish, production deploy hoặc cấp Windows privilege.

## Baseline của lượt trước (phải xác minh lại khi bắt đầu)

- Candidate target/ItemGuard-LITE-1.0.0.jar SHA256 cf26d68f0d7fe8170a729b11047cf76f64a13ba21ec30549e8062f4ae19e0451.
- Java 642 tests, zero failures/errors/skips; 10 Python script tests; artifact verifier ARTIFACT_CONSISTENT_OFFLINE_ONLY.
- LiteCommand đã bỏ restore dispatcher/help/tab và jump callback/teleport/CTA; descriptor bỏ restore/teleport permissions. Full source không bị xóa.
- Source guard không thay thế behavioral test; runtime cũ không chứng nhận candidate mới.
- ZIP release cũ chưa cập nhật. Listing còn sai số slot 45 (layout hiện 28), SHA cũ và trạng thái crafting cần đối chiếu evidence.
- Restore trước đó là verdict-only, không thực sự phát item; tránh ghi sai lịch sử rằng đã loại bỏ một đường cấp item thật.
- English visual acceptance có ghi trong listing cho artifact cũ; VI chưa được nghiệm thu. Không yêu cầu nghiệm thu lại VI để chặn English.

## Milestone 1 — Khóa phạm vi và gate phát hành (làm đầu tiên)

Files: src/main/java/com/itemguard/lite/LiteCommand.java; src/main/resources/lite/plugin.yml; scripts/test_lite_release_surface.py; scripts/verify_lite_artifact.py; docs/release/2026-09-12-lite-listing-draft.md; docs/RISK_REGISTER.md; .hermes/WORKING_STATE.md.

- [ ] Recheck Git/source/candidate; đọc decision và review liên quan để phát hiện task khác đang sửa cùng vùng.
- [ ] Lập bảng gate LITE riêng: requirement → test/runtime evidence → exact artifact → trạng thái. Không dùng gate Full không áp dụng để kéo dài release; cũng không tự miễn Critical gate đang áp dụng LITE.
- [ ] Thêm behavioral regression: OP/wildcard vẫn không gọi restore DB path; timeline click không load chunk/teleport; help/tab không quảng bá tính năng đã loại. Giữ test quyền/privacy/GUI không lấy item.
- [ ] Audit listener/service thực đăng ký bởi ItemGuardLite để xác nhận surface không còn mutation ngoài tracking đã công bố.
- [ ] Sửa listing/README đúng source: 28 ô, NOTIFY, giới hạn crafting, Paper-only. Không cập nhật runtime claim bằng build xanh.

Acceptance: scope thống nhất giữa source, tests, descriptor và docs; review không còn blocker phạm vi. Không thêm tính năng mới.

## Milestone 2 — Chứng minh candidate trên Paper

Files: tools/lite-runtime/manual.py; tools/lite-runtime/MANUAL_LIFECYCLE.md; tools/lite-runtime/ (đọc launcher/verifier thực trước chỉnh); src/test/java/com/itemguard/lite/; docs/reviews/ (receipt mới).

- [ ] Đọc protocol, pin candidate exact SHA, tạo fixture mới theo approval hợp lệ; không dùng fixture consumed.
- [ ] Smoke startup/shutdown, permissions, ID, history, owner privacy/redaction thực sự có foreign actor, GUI readonly/paging/back/close và NOTIFY không xóa item.
- [ ] Kiểm custody bằng DB thô: self pick-up không tăng; chuyển người tăng đúng; chest/death/hopper không tăng sai. Không suy DB semantics từ chat count.
- [ ] Restart: ID/history giữ, SQLite integrity ok. Kiểm restore/teleport không khả dụng ngay cả OP.
- [ ] Craft normal/shift: xác minh hành vi fail-closed giữ nguyên ingredient/output, không gọi đó là crafting issuance hỗ trợ đầy đủ.
- [ ] Cleanup trong finally: graceful stop, đợi save, xác minh PID của fixture biến mất và port đóng. Manual chỉ khi Thanh yêu cầu, mặc định 30 phút, không gia hạn ngầm.

Acceptance: fresh verifier nhận đúng artifact, đủ negative/positive case, cleanup có receipt. Nếu client cần xác nhận layout đã thay thì gom một lượt nghiệm thu English, không chia nhiều lời mời.

## Milestone 3 — Natural-break và durability: xử lý blocker có giới hạn

Sources: docs/design/2026-09-01-natural-break-successor-35-execution-authority.md; docs/RISK_REGISTER.md IG-R016; tools/execution-authority/guardian/; tools/execution-authority/build/offline-test.sh; tools/execution-authority/control/; src/main/java/com/itemguard/listeners/ItemListener.java; src/main/java/com/itemguard/services/ItemTrackingService.java.

- [ ] Phân tích dependency thật giữa IG-R016 và LITE; ghi rõ phần nào là product risk, phần nào là proof infrastructure.
- [ ] Đối chiếu implementation với design, không nối thêm policy constants chỉ vì đó là việc dễ kiểm offline. Ví dụ SPEAKER cần 5 handle trong khi SILENT cần 3; không gọi 3-handle policy là toàn bộ transport.
- [ ] Chẩn đoán toolchain bằng error/log chính xác, không đổi Temp/AV/quyền để né policy. Nếu thiếu quyền launch vẫn tồn tại: nêu môi trường kiểm thử được phép cần có, xin một quyết định rõ thay vì thêm vô hạn abstraction offline.
- [ ] Hoàn thiện entrypoint/composition/recovery cần thiết của guardian theo design đã duyệt và review độc lập trước runtime. Không claim coordinator unit tests là authority runtime proof.
- [ ] Chỉ sau gate host hợp lệ mới chạy successor namespace mới: block-break thật → ItemListener invocation → stable entity → request/outcome → publication → stopped DB/NBT → restart → cleanup.
- [ ] Kiểm crash/unload/concurrency theo risk áp dụng LITE; deterministic fixture và ngân sách tải rõ, không hứa chống power-loss nếu chưa đo.

Acceptance: Critical/High thuộc release được đóng bằng evidence hoặc quyết định thu hẹp scope được duyệt kèm enforcement/test. Ghi hạn chế không đủ để tự miễn lỗi mất đồ/dupe. Nếu blocked, làm các phần release độc lập; không báo release-ready.

## Milestone 4 — Release candidate và hồ sơ SpigotMC

Files: scripts/package_lite.py; scripts/verify_lite_artifact.py; scripts/test_verify_lite_artifact.py; docs/release/ItemGuard-LITE-1.0.0-README.txt; docs/release/2026-09-12-lite-listing-draft.md; release/.

- [ ] Review độc lập exact source/artifact, fix findings, giữ manifest hashes xuyên review.
- [ ] Full build + verifier + tests; không sửa source trong lúc gate đang chạy.
- [ ] Đóng gói ZIP từ đúng JAR đã kiểm; read-back CRC, entries, embedded SHA; tạo release receipt liên kết runtime evidence với đúng bytes (so sánh entries nếu metadata ZIP làm đổi hash).
- [ ] Nội dung English: hướng dẫn cài, backup/upgrade, commands/permissions, screenshot thật, limitations, support và changelog. Không quảng cáo vanilla Spigot chỉ vì đăng trên SpigotMC.
- [ ] Owner duyệt listing/support boundary/craft behavior và upload. Chỉ hỗ trợ Paper 1.21.11/Java21 ở mốc này trừ khi đã có evidence mở rộng.
- [ ] Nếu thực hiện upload: đọc lại exact resource URL/version/file và ghi receipt; chưa có URL thì chưa published.

Acceptance: RELEASE_READY khác PUBLISHED. Published chỉ khi external resource được đọc lại thành công và artifact đúng.

## Milestone 5 — Sau phát hành: ổn định trước mở rộng

- [ ] Thiết lập issue template: version, Paper/Java, bước tái hiện, log đã che dữ liệu riêng; không cần telemetry mặc định.
- [ ] Quy trình hotfix: repro → regression → fix → full/runtime đúng ảnh hưởng → changelog → owner approval.
- [ ] Upgrade/backup/restore DB thử bằng fixture disposable; không downgrade schema bằng tay.
- [ ] Đo hiệu năng trên workload công bố: item/history counts, scan cost, queue, query latency; không quảng cáo số người chơi từ benchmark bot đơn lẻ.
- [ ] Kiểm Vietnamese một lượt riêng sau English; casing thường, không small caps.

## Milestone 6 — Compatibility và Full (sau LITE ổn định)

- [ ] Nghiên cứu hỗ trợ Paper 1.21.1 trở lên theo mong muốn trước đây; ma trận API/JDK/descriptor/runtime từng nhóm, không đổi api-version để giả tương thích.
- [ ] Full có roadmap riêng: restore/issuance chỉ sau durable transaction, anti-dupe, crash/retry và review độc lập; teleport cần lifecycle/permissions/safe landing tests. Không tự đưa ngược vào LITE.
- [ ] Tích hợp storage/MMOItems chỉ khi có contract và nhu cầu đã xác minh, không hứa tìm mọi item ở mọi storage.

## Lệnh xác minh đã có

```bash
export JAVA_HOME='C:/Program Files/Java/jdk-21'
export PATH="$JAVA_HOME/bin:$PATH"
python -m unittest discover -s scripts -p 'test_*.py'
python scripts/package_lite.py
python scripts/verify_lite_artifact.py
python -m unittest discover -s tools/lite-runtime -p 'test_*.py'
git diff --check
codegraph sync "E:/AI.WORK/ItemGuard"
```

package_lite.py tự chạy Maven clean verify, không chạy thêm clean build song song. Runtime command phải lấy từ launcher/help/protocol hiện tại, không đoán hoặc tái dùng command của fixture cũ.

## Định nghĩa hoàn tất và điểm bắt đầu

Hoàn tất mục tiêu đầu tiên = bản LITE có gate release khép kín + package/docs chính xác + được Thanh duyệt và đăng, không phải chỉ thêm tests hoặc C# classes. Không đặt ngày hoàn tất giả khi host authority còn blocked.

Bắt đầu tại Milestone 1: behavioral tests cho read-only boundary và bảng gate LITE. Sau đó đi tuần tự; ưu tiên deliverable sản phẩm, chỉ quay lại guardian khi dependency đã được chỉ rõ. Mỗi checkpoint ghi hoàn thành/chưa hoàn thành, không replay toàn bộ quy trình trong chat.
