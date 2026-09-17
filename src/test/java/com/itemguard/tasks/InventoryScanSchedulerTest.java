package com.itemguard.tasks;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryScanSchedulerTest {

    @Test
    void schedulesOnlyThroughSynchronousBoundary() {
        List<String> calls = new ArrayList<>();
        InventoryScanScheduler scheduler = new InventoryScanScheduler(
            (task, delay, period) -> {
                calls.add("sync:" + delay + ':' + period);
                return () -> calls.add("cancel");
            }
        );

        InventoryScanScheduler.ScheduledTask scheduled =
            scheduler.schedule(() -> {}, 600L);

        assertEquals(List.of("sync:600:600"), calls);
        scheduled.cancel();
        assertEquals(List.of("sync:600:600", "cancel"), calls);
    }
}
