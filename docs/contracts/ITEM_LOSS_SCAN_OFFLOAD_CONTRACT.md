# Contract seam — ItemLossScanOffloadCoordinator (PROPOSED, chưa tồn tại trong ea0)

Parent RED `ItemLossScanMainThreadDatabaseTest` (5 tests / 1 failure / 0 errors) pin ba lời gọi
`getHistory` → `updateItemLastAction` → `logHistory` chạy inline trên scan thread của
`runTaskTimer`. Ba control còn lại pass. Không thể viết test regression về **thứ tự async** mà
chỉ dùng API hiện có: `ItemLossListener.scanOnlinePlayers` là private, `DatabaseManager` đồng bộ,
và không có generation nào gắn với baseline `lastSeen`.

Seam tối thiểu phải thêm (production, ngoài phạm vi lần này — ea0 frozen):

```java
package com.itemguard.listeners;

/** Read + write đều off-thread. Trả future; impl dùng SerialDatabaseExecutor sẵn có. */
public interface LossJournal {
    java.util.concurrent.CompletableFuture<String> lastRecordedAction(String code);
    java.util.concurrent.CompletableFuture<Boolean> recordLoss(LossRecord record);
}

public record LossRecord(String code, java.util.UUID itemUuid,
                         com.itemguard.restore.LossReason reason,
                         java.util.UUID playerUuid, long generation) {}

/** Một ứng viên mất tích do scan thấy; mọi method chỉ được gọi trên main thread. */
public interface LossCandidate {
    String code();
    java.util.UUID itemUuid();
    java.util.UUID playerUuid();
    long generation();
    boolean stillMissing();   // revalidate trên main thread trước khi ghi
    void recorded();          // exactly-once
    void rearmBaseline();     // trả code về lastSeen khi bỏ cuộc (queue reject/timeout)
}

public final class ItemLossScanOffloadCoordinator {
    public ItemLossScanOffloadCoordinator(LossJournal journal,
                                          java.util.function.Consumer<Runnable> mainThreadDispatcher,
                                          java.util.function.BooleanSupplier pluginEnabled,
                                          com.itemguard.dupe.ScanEpochGenerator epochs);
    public long beginGeneration(java.util.UUID playerUuid);
    public boolean request(LossCandidate candidate);
    public void observePresence(String code, java.util.UUID itemUuid, java.util.UUID playerUuid);
    public void observeExplainedDeparture(String code, java.util.UUID itemUuid, java.util.UUID playerUuid);
    public void invalidate(java.util.UUID playerUuid);
    public void shutdown();
}
```

Tái sử dụng, không viết mới: `ScanEpochGenerator` (generation đơn điệu),
`SerialDatabaseExecutor` (serialize JDBC), `LossReason`, pattern callback →
`mainThreadDispatcher` của `AsyncTagPublicationCoordinator`.

Không làm: write-through cache chạm mọi listener. `observePresence` /
`observeExplainedDeparture` chỉ được gọi từ scan và từ DROP path — đủ để bác kết quả cũ.

Test đề xuất (chưa đưa vào `src/test`, xem `sketches/itemloss-offload/`) khoá 7 tính chất:
read off-thread, write off-thread, không chạm Bukkit off-thread, reappearance + DROP bác kết quả
stale, generation cô lập callback cũ, queue reject/timeout rearm baseline thay vì mất, quit/disable
invalidate, exactly-once.
