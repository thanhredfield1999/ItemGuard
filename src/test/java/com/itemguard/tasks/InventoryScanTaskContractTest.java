package com.itemguard.tasks;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryScanTaskContractTest {


    @Test
    void playerInventoryReconciliationDoesNotDependOnContainerScanGate()
        throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/services/ItemTrackingService.java"
        ));
        int method = source.indexOf(
            "private void scanPlayerInventory(Player player, Long scanEpoch)"
        );
        int nextMethod = source.indexOf("public int scanContainer(", method);
        String body = source.substring(method, nextMethod);

        assertTrue(method >= 0, "player inventory scan method must exist");
        assertTrue(!body.contains("isContainerScanEnabled"),
            "player reconciliation must remain active when container scans are disabled");
    }


    @Test
    void openContainerObservationPathHonorsContainerScanGateBeforeBukkitAccess()
        throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/services/ItemTrackingService.java"
        ));
        int method = source.indexOf(
            "public void scanOpenBlockContainerInventory(Player player, long scanEpoch)"
        );
        int guard = source.indexOf(
            "if (!plugin.getConfigs().isContainerScanEnabled()) return;",
            method
        );
        int inventoryAccess = source.indexOf("player.getOpenInventory()", method);

        assertTrue(method >= 0, "container observation method must exist");
        assertTrue(guard > method && guard < inventoryAccess,
            "container scan gate must fail closed before Bukkit inventory access");
    }
}
