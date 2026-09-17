package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoubleChestPhysicalSourceWiringContractTest {

    @Test
    void resolverUsesPhysicalSideInventoryLocationAndLocalSlot() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/tracking/BlockContainerPhysicalSlotResolver.java"
        ));

        assertTrue(source.contains("inventory instanceof DoubleChestInventory"));
        assertTrue(source.contains("doubleChest.getLeftSide()"));
        assertTrue(source.contains("doubleChest.getRightSide()"));
        assertTrue(source.contains("DoubleChestSlotPolicy"));
        assertTrue(source.contains("sideInventory.getLocation()"));
        assertTrue(source.contains("sideInventory.getHolder() instanceof Container"));
    }

    /**
     * Rewritten 2026-09-17 (M2 of the second review). This test used to require the listener to
     * resolve a container slot for a click, which was true of code that could never run: the branch
     * it lived in was reached only from an `else` after an unconditional `return`. Deleting the dead
     * branch left the assertion failing, which is the honest outcome — the invariant it claimed was
     * never exercised. What is true, and is what this now pins, is that the container sweep is the
     * only path that publishes a container slot.
     */
    @Test
    void theContainerSweepIsTheOnlyPathThatPublishesAContainerSlot() throws Exception {
        String listener = Files.readString(Path.of(
            "src/main/java/com/itemguard/listeners/ItemListener.java"
        ));
        String tracking = Files.readString(Path.of(
            "src/main/java/com/itemguard/services/ItemTrackingService.java"
        ));

        assertFalse(listener.contains("requestBlockContainerSlotTag("),
            "a click must not publish a container slot: that path was dead code until it was "
                + "removed, and an untagged item clicked inside a container is left to the sweep");
        assertFalse(listener.contains("BlockContainerPhysicalSlotResolver"));
        assertTrue(tracking.contains("blockContainerSlotResolver.resolve("));
        assertTrue(method(tracking,
            "public int scanContainer(org.bukkit.block.Block block)",
            "public int scanContainerInventory(").contains(
                "blockContainerSlotResolver.resolve("
            ));
        String inventoryScan = method(tracking,
            "public int scanContainerInventory(",
            "public void scanOpenBlockContainerInventory(");
        assertTrue(inventoryScan.contains("requestBlockContainerSlotTag("));
        assertTrue(inventoryScan.contains("location"));
        assertTrue(inventoryScan.contains("resolved.localSlot()"));
        assertTrue(method(tracking,
            "public void scanOpenBlockContainerInventory(",
            "private String generateCode").contains(
                "resolved.localSlot()"
            ));
    }

    @Test
    void containerScanGateUsesControlledRuntimeConfigurationKey() throws Exception {
        String config = Files.readString(Path.of(
            "src/main/java/com/itemguard/ConfigManager.java"
        ));

        assertTrue(method(config,
            "public boolean isContainerScanEnabled()",
            "public int getInventoryScanInterval()").contains(
                "performance.container-scan-enabled"
            ));
    }

    @Test
    void blockPublicationReResolvesLoadedPhysicalSlotBeforeMatchAndWrite()
        throws Exception {
        String resolver = Files.readString(Path.of(
            "src/main/java/com/itemguard/tracking/BlockContainerPhysicalSlotResolver.java"
        ));
        String tracking = Files.readString(Path.of(
            "src/main/java/com/itemguard/services/ItemTrackingService.java"
        ));

        String loadedResolver = method(resolver,
            "public Optional<ResolvedSlot> resolveLoaded(",
            "private Optional<ResolvedSlot> resolveSide");
        assertTrue(loadedResolver.contains("world.isChunkLoaded("));
        assertTrue(loadedResolver.contains("getState(false)"));
        assertTrue(loadedResolver.contains("getBlockInventory()"));

        String publication = method(tracking,
            "public boolean requestBlockContainerSlotTag(",
            "public boolean requestPlayerSlotTag(");
        assertTrue(count(publication,
            "blockContainerSlotResolver.resolveLoaded(") >= 2,
            "must re-resolve authority for both match and physical write");
        assertTrue(publication.contains("current.inventory().setItem("));
        assertTrue(publication.contains("inventoryPhysicalSourcePolicy.matches("));
    }

    @Test
    void genericInventoryPublicationIsNotAPublicBlockContainerBypass()
        throws Exception {
        String tracking = Files.readString(Path.of(
            "src/main/java/com/itemguard/services/ItemTrackingService.java"
        ));

        assertTrue(!tracking.contains("public boolean requestInventorySlotTag("));
        assertTrue(tracking.contains("private boolean requestPlayerInventorySlotTag("));
    }

    private String method(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + startToken.length());
        assertTrue(start >= 0, "missing method " + startToken);
        assertTrue(end > start, "missing method boundary " + endToken);
        return source.substring(start, end);
    }

    private int count(String source, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
