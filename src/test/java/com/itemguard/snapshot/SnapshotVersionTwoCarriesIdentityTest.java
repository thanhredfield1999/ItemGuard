package com.itemguard.snapshot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The version-2 payload must carry the PersistentDataContainer.
 *
 * <p>ItemGuard stores an item's identity — its code and UUID — in the item's PDC. A snapshot
 * that omits the PDC is a snapshot of a different item: two distinct tracked swords would
 * serialise to the same bytes, and the digest could no longer tell them apart.
 *
 * <p>This matters because {@code ItemStack.serialize()} is Bukkit's
 * {@code ConfigurationSerializable} form, which is NOT guaranteed to include arbitrary PDC
 * entries the way Paper's {@code serializeAsBytes()} (full NBT) does. A first attempt at the
 * Spigot encoding used {@code serialize()} directly and the runtime probe failed with
 * {@code LITE_PROBE_FAIL identity-deadline} — no exception, no log line, items simply never
 * became identity-ready. Another silent failure.
 *
 * <p>The test works on the serialised map rather than a live server item, and asserts the
 * property that actually matters: whatever encoding is used, a PDC entry present on the item
 * must appear somewhere in the bytes that get hashed.
 */
class SnapshotVersionTwoCarriesIdentityTest {

    @Test
    @DisplayName("a PDC entry survives into the hashed bytes")
    void persistentDataReachesThePayload() {
        // Shaped like what a tagged ItemGuard item serialises to: the identity lives under
        // meta -> PublicBukkitValues, which is Bukkit's own name for the PDC section.
        Map<String, Object> pdc = new java.util.LinkedHashMap<>();
        pdc.put("itemguard:code", "YTUUS3");
        pdc.put("itemguard:item_uuid", "8bf29b79-df33-4bf6-a779-538c562ad57f");

        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("meta-type", "UNSPECIFIC");
        meta.put("PublicBukkitValues", pdc);

        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("v", 4325);
        item.put("type", "DIAMOND_SWORD");
        item.put("meta", meta);

        org.bukkit.configuration.file.YamlConfiguration document =
            new org.bukkit.configuration.file.YamlConfiguration();
        document.set("item", item);
        String rendered = PaperItemSnapshotCodec.canonicaliseDocument(document.saveToString());

        assertTrue(
            rendered.contains("YTUUS3"),
            "the item's tracking code must be inside the hashed payload, or two different "
                + "tracked items hash alike and identity is lost:\n" + rendered
        );
        assertTrue(
            rendered.contains("8bf29b79-df33-4bf6-a779-538c562ad57f"),
            "the item's UUID must be inside the hashed payload:\n" + rendered
        );
    }
}
