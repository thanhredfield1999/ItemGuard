# Successor-35 — independent findings disposition

## Result

Independent Claude Code result subtype `success`, `modelUsage` có `claude-opus-5` (review) và Haiku ancillary call; verdict `BLOCK`, không Paper/product/release authorization. Input design SHA-256 `7db9404cf0bf6736e3d42a87db16c65b0e3d6d72daeda1820a42b4fa944429aa`. Raw output ở `%LOCALAPPDATA%/Temp/itemguard-successor35-opus5-review-20260908.json`; archived copy ở cùng thư mục review, đuôi `.json`.

## Disposition của parent

- B1 CONFIRMED design ambiguity: canonical journal encoding chưa pin number/string/key ordering cho independent implementations. Không áp dụng mù byte-slice suffix constant của reviewer; cần normative codec spec và vectors trước TDD. Recovery-manifest DAG đang có deletion rule, không tự thay nó khi chưa review.
- B2 CONFIRMED incomplete-history absence risk, nhưng REJECTED causal example cụ thể: nếu C chưa durable SPAWN_INTENT thì guardian đúng contract KHÔNG được spawn, nên không thể hợp lệ có E tiến tới RELEASE trong cùng run như ví dụ reviewer. Phản ví dụ hợp lệ là journal suffix bị mất sau execution (quarantine/truncation thuộc threat model). Cần completeness/terminal authority trước FAIL_BEFORE_PAPER; chưa sửa reducer spec cho tới chốt consistency toàn bộ 4 axes/summary.
- B3 CONFIRMED ordering underspecification: drain không giữ journal lock nhưng seal cần durable activity. Hướng sửa là drain chỉ ghi factual counters; serialized seal operation đảm bảo activity-before-seal. Không đòi seq adjacency nếu activity đã có trước; không nhận test race ngẫu nhiên làm RED deterministic.
- B4 CONFIRMED source contradiction: HANDLE_LIST prose chỉ cấp control ends, trong khi stdio cần inherited ends. Đã sửa design: exact union stdio + optional control, bInheritHandles, STARTF_USESTDHANDLES, EXTENDED_STARTUPINFO_PRESENT; chưa host-runtime verified.
- B5 CONFIRMED silent-role EOF contract defect. Đã thêm required SPEAKER/SILENT, SILENT không control channel. SPEAKER graceful-close còn open; không áp dụng điều kiện EOF-before-wait của reviewer vì scheduling race vẫn có thể false reject.
- B6 INCONCLUSIVE: flags/console policy cần pin, nhưng tuyên bố CREATE_NO_WINDOW vẫn cấp console hoặc conhost luôn tính vào child job cần tài liệu/host probe. Không áp dụng DETACHED_PROCESS/MAX_PATH restriction bằng niềm tin. Microsoft CreateProcessAsUser docs xác nhận writable lpCommandLine, primary restricted-token semantics; không đủ chứng minh toàn finding.
- S1 CONFIRMED specification gap: đã pin một serialized queue cho seq/hash/write/flush cả hai mirrors; failure terminal-failed.
- S2 CONFIRMED cần rõ proof boundary: thread-time equality không tự chứng minh non-execution. Chưa sửa design phần này; cần đối chiếu suspended branch + release-intent uncertainty.
- S3 CONFIRMED ambiguous bootstrap external probe vs no-child gate; cần định nghĩa open-by-PID access check, không spawn child pre-gate.
- S4 PROPOSED availability improvement, không safety blocker: preverify cả hai parents trước leaf create vẫn giữ consumed-on-first-create semantics.
- Parent extra-sibling contradiction CONFIRMED: đã bỏ việc coi unrelated sibling là journal failure, giữ exact-leaf identity verification.

## Verification thực hiện

- Git baseline đọc mới; giữ dirty worktree, không reset/clean/commit.
- `git diff --check` PASS trước correction; sẽ kiểm lại sau correction.
- Design source comparisons xác nhận đồng thời hai câu sibling mâu thuẫn trước correction.
- Chưa có product source change; chưa build/unit/Paper/runtime test mới.
- Không có successor bundle sealed, không runtime namespace, không AV mutation.

## Blocker và bước kế

P0 đang BLOCK tại design, không tự vượt gate để viết guardian production. Hoàn tất normative canonical codec + incomplete-history classifier + activity-before-seal + SPEAKER clean-close + console/bootstrap proof, review lại exact revised design bằng Opus 5. Khi gate cho phép, slice đầu pure in-memory codec RED→GREEN trong source tree mới được chốt; không consume attempt-15. Đây là checkpoint trung gian thật, không phải completion của roadmap.
