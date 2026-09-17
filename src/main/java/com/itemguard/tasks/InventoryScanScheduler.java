package com.itemguard.tasks;

public final class InventoryScanScheduler {

    private final SyncRepeatingScheduler scheduler;

    public InventoryScanScheduler(SyncRepeatingScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public ScheduledTask schedule(Runnable task, long intervalTicks) {
        return scheduler.scheduleSync(task, intervalTicks, intervalTicks);
    }

    @FunctionalInterface
    public interface SyncRepeatingScheduler {
        ScheduledTask scheduleSync(Runnable task, long delayTicks, long periodTicks);
    }

    @FunctionalInterface
    public interface ScheduledTask {
        void cancel();
    }
}
