# Natural-break evidence scaffold: preflight shape failure và bundle integrity loss

Ngày: 2026-09-01

## Trạng thái

`SCAFFOLD DEFECT CORRECTED OFFLINE / EXECUTION HISTORY GAP OPEN / PRODUCT NOT_ISSUED`.

Incident này chỉ thuộc controlled evidence scaffold cho natural container break. Không có bằng chứng defect ItemGuard product từ Attempt 13 hoặc Attempt 14. Không có deploy, restart, mutation hoặc verification production.

## Phạm vi

- Controlled original clone: `E:\AI.WORK\itemguard-paper-smoke`.
- Private namespaces:
  - `attempt-13`, được bundle 31 authorize đúng một lần;
  - `attempt-14`, được bundle 34 authorize đúng một lần.
- Candidate ItemGuard giữ nguyên SHA-256 `2e1fb7d8717a02b316bef9c017d09d67b8705c694519433276bd6e316d5adc5e`.
- Cả hai namespace và receipt tương ứng đã consumed; không được rerun hoặc reuse.

## Incident A — Attempt 13: file-only preflight seal

### Symptom

Pipeline bundle 31 dừng trong `baseline.py` trước `trigger.py`. `runtime-verdict.json` ghi operation `FAIL`, restore `PASS`; `paperProcessLaunched=false`, `phaseRunnerExecuted=false`, `triggerExecuted=false` được đóng trong successor evidence.

Stack trace factual kết thúc tại `preflight_snapshot.py:595`:

`seal_owner_resistant_tree(...)` gọi `next(...)` để chọn một descendant directory và ném `StopIteration`.

### Root cause

`seal_owner_resistant_tree` giả định mọi tree hợp lệ luôn có ít nhất một descendant directory. Preflight authority của Attempt 13 hợp lệ nhưng chỉ gồm root và regular files:

- `descendantDirectories=[]`;
- `filesOnlyAtSeal=true`;
- chín regular files đã được snapshot.

Fixture trước đó chỉ phủ tree có `root/child/target`, nên không bắt được shape file-only mà baseline thật tạo ra.

### Correction và regression evidence

Successor thay directory probe thành optional, vẫn bắt buộc root/file regrant và mutation denial. Bundle 34 offline verification ghi:

- `owner-resistant-file-only-tree-contract.py`: `11` checks PASS;
- `test-only-owner-rights-protect-files-only-contract.py`: `10` checks PASS;
- directory control: `11` checks PASS;
- current Python contracts: `66/66` PASS;
- static contract: `380` checks PASS.

Đây là correction/verification scaffold offline, không phải Paper runtime hoặc product PASS.

### Recovery

- Original tree unchanged: `1376` files, `379888167` bytes, SHA-256 `70cfeed0a33c4f4554ccb1758b877bb557043ba20500325d92d6e908e5980a5d`.
- Runtime DB identity continuity: `[1634470768, 562949954505529]`, links `1`, bytes `1204224`, SHA-256 `86bf625f9eca20b9803971d8828f709c8c7b55304ec9a6ef0b2e688075de7eee`.
- Final private runtime seal: `494` files, `307792002` bytes, manifest SHA-256 `71ecee0786778482478efb53add9b04bfca4c0595b77498f89b7f1829d9ed674`, owner-resistant.
- Attempt 13 classification: `FAIL_BEFORE_PAPER`; không chạy lại.

## Incident B — Attempt 14: antivirus quarantine và execution-history gap

### Symptom

Bundle 34 từng có independent `PASS_FOR_CONTROLLED_PAPER` chỉ cho `attempt-14` với exact set `279 payload + manifest = 280`. Sau invocation, bundle hiện còn `235` files và thiếu đúng `45` payload manifest, gồm `pipeline.py`; không có extra file hoặc hash mismatch trong `234` payload còn lại.

Attempt 14 hiện không có:

- `runtime-activation.json`;
- trigger receipt;
- Paper prepare log;
- `runtime-verdict.json`;
- namespace `classification.json`.

### Observed cause của integrity loss

Read-only Bitdefender audit đối chiếu đủ `45/45` quarantine metadata:

- detection `Atc4.Detection`;
- exact original bundle path;
- embedded SHA-256 trùng manifest SHA-256;
- zero unexplained missing payload;
- metadata mtime từ `2026-09-01T02:03:54.147171+07:00` tới `2026-09-01T02:03:59.122898+07:00`.

Điều này chứng minh bundle integrity loss tương ứng với quarantine metadata. Nó không tự chứng minh Bitdefender đã terminate pipeline process.

Windows Event 1074 ghi user-initiated host restart lúc `2026-09-01T02:15:54.7484319+07:00`. Restart xảy ra sau quarantine metadata; event này cũng không xác định process đã dừng ở instruction nào.

### Evidence-design root cause

Scaffold không có sealed contemporaneous execution journal độc lập với executable bundle để chứng minh các transition:

1. baseline completed;
2. runtime activation persisted;
3. trigger invocation began/completed;
4. phase runner/Paper spawn began;
5. process exit/finalization.

Current artifact absence cộng với reviewed code order hỗ trợ inference pre-Paper chỉ khi các receipt không bị hậu-xóa hoặc sửa. Vì bundle đã mất integrity và không có journal độc lập, execution history phải giữ `PAPER_EXECUTION_NOT_ESTABLISHED_BUNDLE_INTEGRITY_LOSS`, không được nâng thành `FAIL_BEFORE_PAPER` hoặc Paper launch.

### Recovery và exact post-state

Fresh read-only verification sau recovery:

- original full tree exact baseline: `1376` files / `379888167` bytes / SHA-256 `70cfeed0a33c4f4554ccb1758b877bb557043ba20500325d92d6e908e5980a5d`;
- original ItemGuard JAR, `plugins/ItemGuardSmokeProbe.jar`, config và DB đều exact baseline;
- original/runtime DB integrity `ok`, logical SHA-256 `944413c7d472f2a3af42ef42e998449ba18c0dc080529295de5dd9b13682f3e0`, counts exact, triggers/prepared/active claims `0`;
- runtime DB giữ exact NTFS identity `[1634470768, 1407374884637421]`, links `1`, bytes `1204224`;
- backup archive trong Attempt 14 và external authority đều SHA-256 `55dd7e03b7635da781d5c4438dce6c0cdd6310d9f236045f7dfb6d4d73a2524e`;
- final runtime manifest exact: `494` files / `307792002` bytes / SHA-256 `71ecee0786778482478efb53add9b04bfca4c0595b77498f89b7f1829d9ed674`;
- `988/988` runtime objects có `OWNER RIGHTS:(DENY)(WDAC,WO)`, zero ACL error, symlink hoặc reparse point;
- blocking probes ports `57485/36104/57486/57384/36102` đều trả native Windows `10061`;
- không có Java process thuộc Attempt 14 hoặc original controlled Paper.

`restore-receipt.json` có `restored=false`, vì original không đổi; đây không phải claim runtime restore PASS. Attempt 14 được consume fail-closed và không chạy lại.

## Required successor controls

Trước namespace mới:

1. Không restore/whitelist quarantine, không disable/bypass antivirus, không tái tạo bundle 34.
2. Bundle/receipt/namespace mới phải bind exact artifacts và exact scope.
3. TDD file-only, quarantine/integrity-loss và host/process interruption paths trước implementation.
4. Ghi sealed contemporaneous execution journal ngoài executable payload tree, với monotonic transition, exact bundle/namespace/run-token binding và fsync/atomic publication.
5. Journal absence hoặc sequence gap phải fail closed; không suy từ current file absence.
6. Executable payload phải được verify ngay trước từng transition; integrity drift chặn trước Paper và ghi terminal evidence từ authority không nằm trong drifted bundle.
7. Recovery/final seal phải có authority độc lập, không thực thi code từ bundle đã drift.
8. Chỉ sau full offline verification và independent M4 review mới có thể phát hành authorization cho một private namespace mới. Review PASS không tự động chạy Paper.

## Evidence

- Attempt 13 runtime verdict: `E:\AI.WORK\evidence\itemguard-natural-container-break-telemetry-20260829\attempt-13\runtime-verdict.json`.
- Attempt 13 durable RCA snapshot: `E:\AI.WORK\evidence\itemguard-natural-container-break-telemetry-20260829\review-bundle-attempt-34\attempt-13-pre-paper-failure.json`.
- Attempt 13 tree shape: `E:\AI.WORK\evidence\itemguard-natural-container-break-telemetry-20260829\review-bundle-attempt-34\attempt-13-preflight-shape.json`.
- Attempt 14 forensic receipt: `docs/evidence/2026-09-01-natural-break-attempt-14-forensic-classification.json`.
- Attempt 14 runtime report: `docs/runtime/2026-09-01-natural-break-attempt-14-execution-not-established-bundle-integrity-loss.md`.
- Bitdefender read-only audit: `C:\Users\thanh\AppData\Local\Temp\itemguard_bundle34_quarantine_audit.json`, SHA-256 `8f59e4dd12200e1ceabf0bc7ce19d14ad8b46599335b629a4161b976ab07cf07`.

## Non-claims

- Không claim Attempt 14 đã hoặc chưa transiently launch Paper.
- Không claim Bitdefender terminate pipeline chỉ từ quarantine metadata.
- Không claim host restart là root cause duy nhất.
- Không claim ItemGuard product defect, runtime PASS, release approval hoặc production verification.
