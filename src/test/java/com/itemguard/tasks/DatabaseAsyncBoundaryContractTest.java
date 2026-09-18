package com.itemguard.tasks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DatabaseAsyncBoundaryContractTest {
    @Test
    void cleanupDoesNotBlockTheBukkitSchedulerOnDatabaseRetention() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/tasks/CleanupTask.java"));

        assertTrue(source.contains("deleteOldHistoryAsync(keepDays)"),
            "cleanup must submit retention work asynchronously");
        assertFalse(source.contains("deleteOldHistory(keepDays)"),
            "cleanup must not wait for database retention on the scheduler thread");
    }

    @Test
    void inventoryScanTaskDoesNotReadThePersistedEpochInItsConstructor() throws Exception {
        String taskSource = Files.readString(Path.of(
            "src/main/java/com/itemguard/tasks/InventoryScanTask.java"));
        String managerSource = Files.readString(Path.of(
            "src/main/java/com/itemguard/data/DatabaseManager.java"));

        assertFalse(taskSource.contains("getMaximumPersistedObservationEpoch()"),
            "inventory task construction must not synchronously read the database");
        assertTrue(managerSource.contains("getMaximumPersistedObservationEpochAsync()"),
            "database manager must expose an asynchronous epoch read");
        assertTrue(taskSource.contains("initializeEpochFloor"),
            "the persisted epoch must be applied before the first scheduled scan");
    }
}
