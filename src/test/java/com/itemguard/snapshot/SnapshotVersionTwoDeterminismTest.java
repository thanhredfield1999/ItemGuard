package com.itemguard.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Snapshot version 2 must hash the same bytes for the same item, every time.
 *
 * <p>Version 2 exists because Spigot has no {@code ItemStack.serializeAsBytes()}. It writes
 * the item through Bukkit's {@code ConfigurationSerializable} map, rendered to YAML. A digest
 * is only useful if that rendering is stable: if key order or number formatting varied
 * between runs, two snapshots of the same item would hash differently and duplicate detection
 * would silently stop working on the one platform this encoding is for. A silent failure is
 * the worst outcome for this plugin — the operator sees a clean log and an empty result.
 *
 * <p>The determinism claim was originally written as a comment. An independent review pointed
 * out that a comment is not evidence, which is correct, so it is pinned here instead.
 *
 * <p>These tests exercise YamlConfiguration directly rather than a live ItemStack: constructing
 * a real CraftItemStack needs a running server, and what is in question is the *rendering*,
 * not Bukkit's own serialisation of an item.
 */
class SnapshotVersionTwoDeterminismTest {

    /** A map shaped like a serialised ItemStack: nested, mixed types, non-alphabetical order. */
    private static Map<String, Object> itemLikeMap() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("display-name", "§6Named Sword");
        meta.put("lore", java.util.List.of("§7line one", "§7line two"));
        Map<String, Object> enchants = new LinkedHashMap<>();
        enchants.put("minecraft:sharpness", 5);
        enchants.put("minecraft:unbreaking", 3);
        meta.put("enchants", enchants);
        Map<String, Object> pdc = new LinkedHashMap<>();
        pdc.put("itemguard:code", "YTUUS3");
        pdc.put("itemguard:uuid", "8bf29b79-df33-4bf6-a779-538c562ad57f");
        meta.put("PublicBukkitValues", pdc);
        meta.put("Damage", 12);
        meta.put("custom-model-data", 1.5D);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("v", 4325);
        item.put("type", "DIAMOND_SWORD");
        item.put("amount", 1);
        item.put("meta", meta);
        return item;
    }

    /**
     * Renders exactly the way PaperItemSnapshotCodec.encode does on the Bukkit path, including
     * the canonicalisation step — otherwise this test would measure something the product does
     * not do, and would have kept passing while the real encoder stayed order-dependent.
     */
    private static byte[] render(Map<String, Object> value) {
        YamlConfiguration document = new YamlConfiguration();
        document.set("item", value);
        return PaperItemSnapshotCodec.canonicaliseDocument(document.saveToString())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the same map renders to identical bytes on repeated calls")
    void renderingIsStableAcrossCalls() {
        Map<String, Object> value = itemLikeMap();
        byte[] first = render(value);
        for (int attempt = 0; attempt < 50; attempt++) {
            assertArrayEquals(first, render(value),
                "YAML rendering drifted between calls; a snapshot digest would be unstable");
        }
    }

    @Test
    @DisplayName("two separately built but equal maps render identically")
    void equalMapsRenderIdentically() {
        // This is the real scenario: the same physical item serialised twice produces two
        // distinct map instances that happen to be equal. They must hash alike.
        assertArrayEquals(render(itemLikeMap()), render(itemLikeMap()),
            "two equal item maps produced different YAML; duplicate detection would break");
    }

    @Test
    @DisplayName("digests agree for equal maps")
    void digestsAgree() {
        ItemSnapshotCodec codec = new ItemSnapshotCodec(1_048_576);
        ItemSnapshot a = codec.captureAs(
            ItemSnapshotCodec.SNAPSHOT_VERSION_BUKKIT_MAP, render(itemLikeMap()));
        ItemSnapshot b = codec.captureAs(
            ItemSnapshotCodec.SNAPSHOT_VERSION_BUKKIT_MAP, render(itemLikeMap()));
        assertArrayEquals(a.sha256(), b.sha256());
    }

    @Test
    @DisplayName("insertion order does not change the rendering")
    void insertionOrderDoesNotLeakIntoTheBytes() {
        // The risk this guards: if YamlConfiguration preserved insertion order, the same item
        // serialised by two code paths that happened to build the map in different orders
        // would hash differently. Compare a LinkedHashMap against a TreeMap holding the same
        // entries — if the bytes match, ordering is normalised and the digest is safe.
        Map<String, Object> ordered = itemLikeMap();
        Map<String, Object> sorted = new TreeMap<>(ordered);
        assertArrayEquals(render(ordered), render(sorted),
            "map iteration order changed the YAML; version-2 digests would depend on how the "
                + "map was built, not on the item");
    }

    @Test
    @DisplayName("a real difference still changes the digest")
    void differentItemsStillDiffer() {
        // The counter-check: determinism must not have been achieved by dropping detail.
        Map<String, Object> changed = itemLikeMap();
        changed.put("amount", 2);
        ItemSnapshotCodec codec = new ItemSnapshotCodec(1_048_576);
        byte[] original = codec.captureAs(2, render(itemLikeMap())).sha256();
        byte[] modified = codec.captureAs(2, render(changed)).sha256();
        assertEquals(false, java.util.Arrays.equals(original, modified),
            "a changed item hashed the same as the original; the encoding is losing data");
    }
}
