# Incident: SQLite cho phép nhiều owner cùng file và trì hoãn lỗi contention

Ngày: 2026-08-28  
Trạng thái: `MITIGATED source/unit + controlled same-host single-owner`  
Production impact: `NOT OBSERVED`; production không bị truy cập/thay đổi.

## Symptom

Pre-fix, hai `SqliteConnectionOwner` độc lập cùng mở một SQLite file. Khi owner A giữ write transaction, owner B chờ khoảng 3.3 giây rồi ném `[SQLITE_BUSY] database is locked`. Lỗi xuất hiện ở operation time thay vì fail-fast tại plugin startup.

## Root cause

`SerialDatabaseExecutor` chỉ serialize operations trong **một owner**. Không có ownership boundary giữa hai owner/JVM process cùng SQLite file. SQLite file locking bảo vệ integrity ở mức engine nhưng không cung cấp contract startup fail-closed cho ItemGuard.

Trong quá trình sửa còn phát hiện ba lỗi lifecycle sibling: init failure rò JDBC handle, caller interruption thả sidecar trước worker kết thúc, và normal close failure không giữ static strong reference đến lock.

## Fix

Thêm sidecar OS `FileLock` giữ suốt owner lifecycle, lấy trước JDBC/schema. Chỉ release sau JDBC close + executor termination. Mọi cleanup/close path không chứng minh đóng sạch đều giữ lock đến process exit.

## Regression và verification

- RED artifacts: eight ownership/init/close/release/callback lifecycle defects are preserved.
- Focused `36/36`; full `283/283`; final lifecycle repeat `10/10`.
- Opus 4.8 final review PASS.
- Controlled Paper/process matrix token `1c24dace-368b-4214-bc1f-710363e937bc` PASS trên local fixed NTFS; DB byte/logical zero-delta; clean/force-kill handoff PASS.
- Evidence archive SHA-256 `96310ba4f7f21a84b4d1a1ecd965c6d53b3b9c7254b892d28bd631e0116826ef`.

## Support boundary

Fix này chứng minh **same-host cooperative single-owner fail-fast**. Không chứng minh hoặc bật multi-writer/multi-server/shared-filesystem support. Production vẫn chưa verified.
