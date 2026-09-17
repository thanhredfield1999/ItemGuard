package com.itemguard.tracking;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundItemMergeWiringContractTest {

    @Test
    void itemListenerFailsClosedBeforeIdentityBearingGroundItemsMerge() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/listeners/ItemListener.java"
        ));

        assertTrue(source.contains("import org.bukkit.event.entity.ItemMergeEvent;"));
        assertTrue(source.contains("private final GroundItemMergePolicy groundItemMergePolicy"));

        // onItemMerge is now the last handler in the class: the Paper-only onEntityAdded that
        // used to follow it moved to PaperEntitySpawnListener. Use the class's closing brace
        // as the end marker instead of the next method signature.
        String body = method(
            source,
            "public void onItemMerge(ItemMergeEvent event)",
            null
        );
        assertTrue(body.contains("event.getEntity().getItemStack()"));
        assertTrue(body.contains("event.getTarget().getItemStack()"));
        assertTrue(body.contains("tracking.hasCodeOrUuid(source)"));
        assertTrue(body.contains("tracking.hasCodeOrUuid(target)"));
        assertTrue(body.contains("groundItemMergePolicy.decide("));
        assertTrue(body.contains("event.setCancelled(true)"));
        assertFalse(body.contains("tracking.shouldTrack("));
        assertFalse(body.contains("requestEntityTag("));
        assertFalse(body.contains("requestPlayerSlotTag("));
        assertFalse(body.contains("requestInventorySlotTag("));
    }

    @Test
    void identityResolutionDistinguishesAbsentKeysFromWrongPersistentDataTypes()
        throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/itemguard/services/ItemTrackingService.java"
        ));
        String body = method(
            source,
            "public IdentityTagResolution resolveIdentityTags(ItemStack item)",
            "public boolean hasCode(ItemStack item)"
        );

        assertTrue(body.contains("pdc.has(codeKey)"));
        assertTrue(body.contains("pdc.has(uuidKey)"));
        assertTrue(body.contains("pdc.has(codeKey, PersistentDataType.STRING)"));
        assertTrue(body.contains("pdc.has(uuidKey, PersistentDataType.STRING)"));
        assertTrue(body.contains("pdc.get(codeKey, PersistentDataType.STRING)"));
        assertTrue(body.contains("pdc.get(uuidKey, PersistentDataType.STRING)"));
    }

    /** Slices out a method body. A null {@code end} means "to the end of the file", for the
     *  case where the method under inspection is the last one in its class. */
    private String method(String source, String start, String end) {
        int from = source.indexOf(start);
        assertTrue(from >= 0, "missing method start " + start);
        if (end == null) {
            return source.substring(from);
        }
        int to = source.indexOf(end, from + start.length());
        assertTrue(to > from, "missing method end " + end);
        return source.substring(from, to);
    }
}
