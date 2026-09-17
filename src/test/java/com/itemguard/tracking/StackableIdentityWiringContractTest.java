package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StackableIdentityWiringContractTest {

    @Test
    void trackingServiceGuardsEveryIdentityLifecycleSeam() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/services/ItemTrackingService.java"
        ));

        assertTrue(source.contains(
            "private final ItemIdentityEligibilityPolicy identityEligibilityPolicy"
        ));
        assertTrue(method(source, "public boolean shouldTrack(ItemStack item)",
            "public String getCodeFromItem").contains(
                "identityEligibilityPolicy.shouldTrack("
            ));
        assertTrue(method(source, "public boolean isIdentityReady(ItemStack item)",
            "public boolean isEntityIdentityReady").contains(
                "item.getMaxStackSize(), item.getAmount()"
            ));
        assertTrue(method(source, "public boolean isEntityIdentityReady(Item entity)",
            "private boolean reconcilePhysicalIdentity").contains(
                "physicalItem.getMaxStackSize(), physicalItem.getAmount()"
            ));
        assertTrue(method(source,
            "private boolean reconcilePhysicalIdentity(",
            "private void markPublished").contains(
                "physicalItem.getMaxStackSize(), physicalItem.getAmount()"
            ));
        assertTrue(method(source,
            "private void scanPlayerInventory(Player player, Long scanEpoch)",
            "public int scanContainer(").contains(
                "item.getMaxStackSize(), item.getAmount()"
            ));
        assertTrue(method(source,
            "public void scanOpenBlockContainerInventory(Player player, long scanEpoch)",
            "private String generateCode").contains(
                "item.getMaxStackSize(), item.getAmount()"
            ));
    }

    @Test
    void configFacadeUsesSameFailClosedPolicy() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/ConfigManager.java"
        ));

        assertTrue(source.contains("ItemIdentityEligibilityPolicy"));
        assertTrue(method(source, "public boolean shouldTrack(ItemStack item)",
            "// ---------- UUID TAG").contains(
                "identityEligibilityPolicy.shouldTrack("
            ));
    }

    @Test
    void hopperChecksTaggedIdentityBeforeNewIdentityEligibility() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/listeners/ContainerListener.java"
        ));
        String body = method(source,
            "public void onInventoryMoveItem(InventoryMoveItemEvent event)",
            "public void onInventoryOpen(InventoryOpenEvent event)");

        int tagged = body.indexOf("if (tracking.hasCodeOrUuid(item))");
        int eligible = body.indexOf("if (!tracking.shouldTrack(item)) return;");
        assertTrue(tagged >= 0, "hopper path must inspect legacy tags");
        assertTrue(eligible > tagged,
            "legacy tagged stackables must fail closed before new-tag eligibility");
    }

    @Test
    void playerAndCraftMutationPathsFailClosedForPartialIdentity() throws Exception {
        String itemListener = Files.readString(Path.of(
            "src/main/java/com/itemguard/listeners/ItemListener.java"
        ));
        String craftListener = Files.readString(Path.of(
            "src/main/java/com/itemguard/listeners/CraftListener.java"
        ));

        assertTrue(count(itemListener, "tracking.hasCodeOrUuid(item)") >= 4,
            "drop/click/drag/use must guard code XOR uuid corruption");
        assertTrue(craftListener.contains("tracking.hasCodeOrUuid(result)"),
            "craft output must guard code XOR uuid corruption");
    }

    private String method(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0, "missing method start " + start);
        assertTrue(to > from, "missing method end " + end);
        return source.substring(from, to);
    }

    private int count(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
