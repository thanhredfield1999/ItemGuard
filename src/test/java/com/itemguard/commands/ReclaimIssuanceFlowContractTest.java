package com.itemguard.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The hand-over's safety is its order, and the order is not visible in any single file: the flow
 * brackets the inventory write, and two commands call that flow.
 *
 * <p>These are deliberately source contracts. The alternative — a Bukkit fixture that watches a
 * player inventory — belongs to the runtime gate, not to the offline suite; what the offline suite
 * can do is make the dangerous reorderings fail loudly here instead of silently at runtime. The three
 * orderings that matter: arm happens off the server thread and before the inventory write, the
 * inventory write happens on the server thread, and settling happens after (and off) it.
 */
class ReclaimIssuanceFlowContractTest {

    private static final Path FLOW =
        Path.of("src/main/java/com/itemguard/commands/ReclaimIssuanceFlow.java");
    private static final Path MATDO =
        Path.of("src/main/java/com/itemguard/commands/MatDoCommand.java");
    private static final Path FINDITEM =
        Path.of("src/main/java/com/itemguard/commands/FindItemCommand.java");

    @Test
    void armingRunsOffThreadAndBeforeTheInventoryWrite() throws Exception {
        String source = Files.readString(FLOW);

        int asyncDispatch = source.indexOf("runTaskAsynchronously");
        int arm = source.indexOf("issuance.arm(");
        int serverHop = source.indexOf("Bukkit.getScheduler().runTask(");
        int inventoryWrite = source.indexOf(".addItem(");

        assertTrue(arm > 0 && serverHop > 0 && inventoryWrite > 0, "flow steps not found");
        assertTrue(arm > asyncDispatch,
            "arming writes to the claim table, so it must run off the server thread");
        assertTrue(inventoryWrite > serverHop,
            "the inventory write must happen on the server thread, after the hop back to it");
    }

    @Test
    void settlingRunsOffThreadAndOnlyInTheSettleStep() throws Exception {
        String source = Files.readString(FLOW);

        int settle = source.indexOf("issuance.settle(");
        int asyncBeforeSettle = source.lastIndexOf("runTaskAsynchronously", settle);

        assertTrue(settle > 0, "the flow must record the outcome");
        assertTrue(asyncBeforeSettle > 0 && asyncBeforeSettle < settle,
            "settling writes to the claim table, so it must run off the server thread");
    }

    @Test
    void theGateIsCheckedThroughTheConfigAndNotAssumed() throws Exception {
        String matDo = Files.readString(MATDO);
        String flow = Files.readString(FLOW);

        assertTrue(matDo.contains("isReclaimIssuanceEnabled()"),
            "the player path must consult the configured gate before issuing");
        assertFalse(matDo.contains("ISSUANCE_GATE_CLOSED"),
            "the closed gate string is gone: the path now either issues or explains why it cannot");
        assertTrue(flow.contains("isReclaimIssuanceEnabled"),
            "the issuing service must be given the gate, not a constant");
    }

    @Test
    void bothCommandsUseOneHandOverProtocol() throws Exception {
        String matDo = Files.readString(MATDO);
        String findItem = Files.readString(FINDITEM);

        assertTrue(matDo.contains("new ReclaimIssuanceFlow(plugin).issue("),
            "the player path must use the shared flow");
        assertTrue(findItem.contains("new ReclaimIssuanceFlow(plugin).issue("),
            "the admin path must use the same flow; a second copy of the order is a second set of bugs");
    }

    @Test
    void absenceIsProvenBeforeTheAdminPathReservesAClaim() throws Exception {
        String source = Files.readString(FINDITEM);

        int gate = source.indexOf("ReclaimCapabilityEvaluator");
        int reserve = source.indexOf("claims.reserve(");
        int issue = source.indexOf("new ReclaimIssuanceFlow(plugin).issue(");

        assertTrue(gate > 0, "giveoldid must prove absence through the capability gate");
        assertTrue(reserve > gate,
            "reserving before proving absence would lock a claim for an item that is still present");
        assertTrue(issue > reserve,
            "issuing before reserving would hand out an item with no claim holding the identity");
        assertTrue(source.contains("ReclaimDecisionStatus.ELIGIBLE"),
            "giveoldid must refuse when the gate is not eligible");
    }

    @Test
    void theAdminPathHasItsOwnPermission() throws Exception {
        String source = Files.readString(FINDITEM);
        String descriptor = Files.readString(Path.of("src/main/resources/plugin.yml"));

        assertTrue(source.contains("itemguard.giveoldid"),
            "handing out an item must not ride on the read-only admin permission");
        assertTrue(descriptor.contains("itemguard.giveoldid:"),
            "the permission must be declared, or it silently belongs to nobody");
    }
}
