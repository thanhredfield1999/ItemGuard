package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HopperTransferWiringContractTest {

    @Test
    void listenerUsesPolicyAndSchedulesSourceScanAfterCancellation() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/listeners/ContainerListener.java"
        ));
        String body = method(source,
            "public void onInventoryMoveItem(InventoryMoveItemEvent event)",
            "public void onInventoryOpen(InventoryOpenEvent event)");

        assertTrue(source.contains("HopperTransferPolicy hopperTransferPolicy"));
        assertTrue(body.contains("hopperTransferPolicy.decide("));
        assertTrue(body.contains("event.setCancelled(true)"));
        assertTrue(body.contains("Inventory source = event.getSource()"));
        assertTrue(body.contains("runTask(plugin, () ->"));
        assertTrue(body.contains("tracking.scanContainerInventory(source, null)"));
        assertFalse(body.contains("tracking.scanContainerInventory(event.getDestination()"));
        assertFalse(body.contains("event.setItem("));
    }

    @Test
    void cooldownCannotPermitAnUntaggedEligibleRetry() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/listeners/ContainerListener.java"
        ));
        String body = method(source,
            "public void onInventoryMoveItem(InventoryMoveItemEvent event)",
            "public void onInventoryOpen(InventoryOpenEvent event)");

        int decision = body.indexOf("hopperTransferPolicy.decide(");
        int cancellation = body.indexOf("event.setCancelled(true)", decision);
        int cooldown = body.indexOf("isCooldownActive(source.getHolder())", decision);
        assertTrue(decision >= 0 && cancellation > decision);
        assertTrue(cooldown > cancellation,
            "cooldown must only dedupe scans after unconditional cancellation");
    }

    @Test
    void singleChestSourceScanKeepsRawSlotAsPhysicalLocalSlot() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/tracking/BlockContainerPhysicalSlotResolver.java"
        ));
        String body = method(source,
            "public Optional<ResolvedSlot> resolve(Inventory inventory, int rawSlot)",
            "private Optional<ResolvedSlot> resolveSide");

        assertTrue(body.contains("return resolveSide(inventory, rawSlot)"),
            "single chest slot 4 must remain local slot 4 at its physical holder");
    }

    @Test
    void listenerRejectsUnsupportedSourceOrDestinationTopologyBeforeAllowingMove()
        throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/listeners/ContainerListener.java"
        ));
        String body = method(source,
            "public void onInventoryMoveItem(InventoryMoveItemEvent event)",
            "public void onInventoryOpen(InventoryOpenEvent event)");

        int sourceInventory = body.indexOf("Inventory source = event.getSource()");
        int destinationInventory = body.indexOf(
            "Inventory destination = event.getDestination()"
        );
        int sourceSupport = body.indexOf(
            "blockContainerSlotResolver.supports(source)"
        );
        int destinationSupport = body.indexOf(
            "blockContainerSlotResolver.supports(destination)"
        );
        int decision = body.indexOf("hopperTransferPolicy.decide(");

        assertTrue(sourceInventory >= 0);
        assertTrue(destinationInventory > sourceInventory);
        assertTrue(sourceSupport > destinationInventory);
        assertTrue(destinationSupport > sourceSupport);
        assertTrue(decision > destinationSupport);
        assertTrue(body.contains("sourceSupported"));
        assertTrue(body.contains("destinationSupported"));
    }

    @Test
    void topologyResolverSupportsOnlyPhysicalBlockContainers() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/tracking/BlockContainerPhysicalSlotResolver.java"
        ));
        String body = method(source,
            "public boolean supports(Inventory inventory)",
            "public Optional<ResolvedSlot> resolve(Inventory inventory, int rawSlot)");

        assertTrue(body.contains("inventory instanceof DoubleChestInventory"));
        assertTrue(body.contains("resolveSide(doubleChest.getLeftSide(), 0)"));
        assertTrue(body.contains("resolveSide(doubleChest.getRightSide(), 0)"));
        assertTrue(body.contains("resolveSide(inventory, 0)"));
    }

    private String method(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0, "missing method start " + start);
        assertTrue(to > from, "missing method end " + end);
        return source.substring(from, to);
    }
}
