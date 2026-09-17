# Controlled SQLite single-owner fail-closed gate — 2026-08-28

## Kết luận

`VERIFIED controlled` cho **một owner hợp tác trên cùng host Windows 11/local fixed NTFS**. ItemGuard hiện từ chối owner/process thứ hai ngay lúc startup bằng OS sidecar file lock thay vì để writer thứ hai chờ rồi lỗi `SQLITE_BUSY` trong gameplay.

Điều này **không** chứng minh multi-writer, multi-server, shared filesystem, NFS/SMB/UNC/mapped drive, mixed old/new JAR hoặc external SQLite writer là an toàn/hỗ trợ.

Overall release verdict vẫn `NOT RELEASE READY`; production chưa deploy/restart/verify.

## Artifact và môi trường

- Candidate SHA-256: `a6dafcf11644712732c1336ef36aa640d59bb37d704c3211ae9a06770fe81907`.
- Paper: `1.21.11-131`; Java 21.
- Controlled clone: `E:/AI.WORK/itemguard-paper-smoke`.
- Filesystem: local fixed NTFS; Win32 identity `616C0B70/01B8000000004251`.
- Authoritative token: `1c24dace-368b-4214-bc1f-710363e937bc`.
- Evidence archive SHA-256: `96310ba4f7f21a84b4d1a1ecd965c6d53b3b9c7254b892d28bd631e0116826ef`.

## Defect và TDD

Pre-fix falsifier mở hai `SqliteConnectionOwner` trên cùng DB; writer B chờ khoảng 3.3 giây rồi `SQLITE_BUSY`, writer A commit một row, integrity `ok`.

Tám regression RED được giữ trong evidence:

1. Owner thứ hai không bị từ chối khi owner đầu còn sống.
2. Future-schema initialization failure để rò JDBC handle trên Windows.
3. Caller bị interrupt trong initialization làm sidecar được thả khi DB worker/JDBC còn chạy.
4. Normal `close(...)=false` chỉ dựa vào reachability của owner thay vì static retention tới process exit.
5. Init cleanup có thể chờ vô hạn sau caller interruption nếu init worker bị kẹt.
6. Close lần hai báo `true` dù close lần đầu đã fail.
7. Process-lock release failure thoát boolean close contract hoặc che mất init root cause.
8. `failureHandler` ném exception có thể thoát trước static lock retention.

Fix nhỏ nhất:

- `<database>.itemguard.lock` được `FileChannel.tryLock()` trước JDBC/schema và giữ suốt lifecycle.
- Sidecar không bị xóa, tránh unlink race; stale sidecar không khóa nếu OS lock không còn.
- Init failure đóng JDBC trên serial DB executor trước khi release.
- Init interruption cleanup được xếp sau init trên cùng executor; mọi cleanup/termination failure giữ strong reference tĩnh đến process exit.
- Normal close chỉ release sau JDBC close + executor termination; mọi nhánh false giữ OS lock đến process exit.

## Verification

- Focused persistence: `36/36` PASS.
- Full Java 21: `283/283` PASS.
- Final owner/lifecycle/cross-process suite: `10/10` vòng lặp PASS.
- Opus 4.8 final correction review: `PASS_FOR_AUTHORITATIVE_RERUN`, blockers rỗng.
- Simultaneous contender race: exactly one `ACQUIRED`, one `REJECTED`; rejection là `already owned`, không `SQLITE_BUSY`.
- Primary Paper sở hữu DB: standalone exact candidate và second isolated Paper cùng exact DB đều bị từ chối startup fail-fast.
- Clean stop handoff và force-kill/PID-exit handoff đều reacquire/integrity `ok`.
- DB file SHA, logical dump và tất cả counts zero-delta; ports đóng; không `-journal`/`-wal`.
- Win32 anti-vacuity xác nhận mọi process/alias trỏ cùng volume serial + file index.
- Clone đã restore byte-exact baseline; sidecar absent sau restore.

## Attempts bị loại

- Candidate `c3ec23bb…` chạy matrix nhưng bị supersede sau self-review phát hiện init-interruption unlock sớm.
- Candidate `a0094098…` chưa chạy authoritative và bị supersede vì normal close failure chưa static-retain lock.
- Opus 4.8/4.6 `429` trên stale candidate không được coi là verdict.
- Java `BasicFileAttributes.fileKey()` trả `null` trên Windows, nên không dùng làm same-file proof; Win32 handle identity thay thế.

## Residual scope

- Multi-writer không được hỗ trợ; topology đúng là exactly one cooperating owner.
- Multi-server/shared/network filesystem chưa verified và không được claim support.
- External tools/old JAR không dùng sidecar vẫn có thể tranh SQLite.
- Hopper/container variants, broader crash/unload stress, external absence, issuance và production còn mở.
