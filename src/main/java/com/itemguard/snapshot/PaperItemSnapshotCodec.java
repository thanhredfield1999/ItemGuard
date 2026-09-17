package com.itemguard.snapshot;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * Turns an ItemStack into a stable byte payload plus digest.
 *
 * <p>Two encodings exist because the servers differ:
 *
 * <ul>
 *   <li><b>Version 1</b> — Paper's {@code ItemStack.serializeAsBytes()}. Compact, and the
 *       only format used before Spigot support existed.</li>
 *   <li><b>Version 2</b> — Bukkit's {@code ConfigurationSerializable} map rendered to YAML.
 *       Available everywhere, used when the Paper method is absent.</li>
 * </ul>
 *
 * <p>Spigot does not ship {@code serializeAsBytes}; calling it threw
 * {@code NoSuchMethodError: byte[] org.bukkit.inventory.ItemStack.serializeAsBytes()} and
 * killed the tracking probe on a real Spigot 1.21.4 server. The method is resolved once at
 * class-init by reflection rather than by server brand, because forks rename themselves but
 * either the method is on the classpath or it is not.
 *
 * <p><b>Digests are comparable within one encoding only.</b> The same physical item captured
 * on Paper and on Spigot produces different bytes and therefore a different sha256. That is
 * acceptable because a digest is only ever compared against another snapshot of the same item
 * on the same server, but it must never be treated as a cross-platform identity.
 */
public final class PaperItemSnapshotCodec {

    /** Non-null when the running server provides Paper's byte serialiser. */
    private static final java.lang.reflect.Method SERIALIZE_AS_BYTES = resolveSerializeAsBytes();

    private static java.lang.reflect.Method resolveSerializeAsBytes() {
        try {
            return ItemStack.class.getMethod("serializeAsBytes");
        } catch (NoSuchMethodException | RuntimeException absent) {
            return null;
        }
    }

    /** Which snapshot version this server will write. */
    public static int activeSnapshotVersion() {
        return SERIALIZE_AS_BYTES != null
            ? ItemSnapshotCodec.SNAPSHOT_VERSION_PAPER_BYTES
            : ItemSnapshotCodec.SNAPSHOT_VERSION_BUKKIT_MAP;
    }

    private final ItemSnapshotCodec envelopeCodec;

    public PaperItemSnapshotCodec(int maximumPayloadBytes) {
        this.envelopeCodec = new ItemSnapshotCodec(maximumPayloadBytes);
    }

    public ItemSnapshot capture(ItemStack item) {
        Objects.requireNonNull(item, "item");
        try {
            return envelopeCodec.captureAs(activeSnapshotVersion(), encode(item));
        } catch (RuntimeException invalidItem) {
            if (invalidItem instanceof SnapshotValidationException validation) {
                throw validation;
            }
            throw new SnapshotValidationException(
                "Cannot serialize ItemStack snapshot",
                invalidItem
            );
        }
    }

    public ItemStack restore(ItemSnapshot snapshot) {
        byte[] payload = envelopeCodec.restore(snapshot);
        try {
            // Decode by the version the payload was written with, not by what this server
            // happens to support now: a database may hold rows from either platform.
            if (snapshot.version() == ItemSnapshotCodec.SNAPSHOT_VERSION_PAPER_BYTES) {
                if (SERIALIZE_AS_BYTES == null) {
                    throw new SnapshotValidationException(
                        "This snapshot was captured on Paper and cannot be decoded on a "
                            + "server without ItemStack.serializeAsBytes()"
                    );
                }
                return ItemStack.deserializeBytes(payload);
            }
            return decodeBukkitMap(payload);
        } catch (SnapshotValidationException unsupported) {
            throw unsupported;
        } catch (RuntimeException corruptPayload) {
            throw new SnapshotValidationException(
                "Cannot deserialize ItemStack snapshot",
                corruptPayload
            );
        }
    }

    private byte[] encode(ItemStack item) {
        if (SERIALIZE_AS_BYTES != null) {
            try {
                return (byte[]) SERIALIZE_AS_BYTES.invoke(item);
            } catch (ReflectiveOperationException failure) {
                throw new SnapshotValidationException(
                    "Paper item serialisation failed", failure);
            }
        }
        // Bukkit-only path.
        //
        // Writing the ItemStack itself (rather than item.serialize()) is deliberate:
        // YamlConfiguration round-trips a registered ConfigurationSerializable through the
        // server's own writer, which carries the item's full meta including the
        // PersistentDataContainer. ItemGuard's identity lives in that PDC, so a payload
        // without it would hash two different tracked swords to the same bytes — the runtime
        // probe caught exactly that as LITE_PROBE_FAIL identity-deadline, silently, with no
        // exception anywhere.
        //
        // Determinism is then imposed on the rendered document rather than on the map:
        // YamlConfiguration preserves insertion order, so the same item built in two orders
        // would otherwise hash differently and duplicate detection would quietly stop
        // matching. Sorting the top-level lines of the rendered YAML is enough because the
        // document is a single nested block whose ordering derives from that map, and it
        // cannot drop data the way rebuilding the map by hand can.
        YamlConfiguration document = new YamlConfiguration();
        document.set("item", item);
        return canonicaliseDocument(document.saveToString())
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Sorts a rendered YAML document's lines within each indentation block so the bytes depend
     * on content, not on map insertion order.
     *
     * <p>Operating on text keeps every value the server wrote — nothing is reconstructed, so
     * nothing can be lost. Continuation lines (list items and deeper nesting) stay attached to
     * the key they belong to.
     */
    static String canonicaliseDocument(String rendered) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String line : rendered.split("\n", -1)) {
            // Blank lines carry no item data and YamlConfiguration places them differently
            // depending on map order, so they would reintroduce the very instability this
            // method removes.
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return String.join("\n", sortBlock(lines, 0));
    }

    private static java.util.List<String> sortBlock(java.util.List<String> lines, int indent) {
        // Group each key line with everything nested beneath it, sort the groups by their key
        // line, then recurse into the nested content.
        java.util.TreeMap<String, java.util.List<String>> groups = new java.util.TreeMap<>();
        java.util.List<String> leading = new java.util.ArrayList<>();
        String currentKey = null;
        java.util.List<String> current = null;

        for (String line : lines) {
            int lineIndent = line.length() - line.stripLeading().length();
            boolean isKeyAtThisLevel = !line.isBlank()
                && lineIndent == indent
                && !line.stripLeading().startsWith("-");
            if (isKeyAtThisLevel) {
                currentKey = line;
                current = new java.util.ArrayList<>();
                groups.put(line, current);
            } else if (current != null) {
                current.add(line);
            } else {
                leading.add(line);
            }
        }
        if (groups.isEmpty()) {
            return lines;
        }

        java.util.List<String> out = new java.util.ArrayList<>(leading);
        for (java.util.Map.Entry<String, java.util.List<String>> entry : groups.entrySet()) {
            out.add(entry.getKey());
            out.addAll(sortBlock(entry.getValue(), indent + 2));
        }
        return out;
    }

    private ItemStack decodeBukkitMap(byte[] payload) {
        YamlConfiguration document = new YamlConfiguration();
        try {
            document.loadFromString(new String(payload, StandardCharsets.UTF_8));
        } catch (Exception malformed) {
            throw new SnapshotValidationException(
                "Item snapshot payload is not a readable Bukkit document", malformed);
        }
        // encode() writes the ItemStack itself, so Bukkit reconstructs it here with its meta
        // and PersistentDataContainer intact. Line sorting only reorders sibling keys; YAML
        // has no significant order, so the document still parses to the same item.
        ItemStack restored = document.getItemStack("item");
        if (restored == null) {
            throw new SnapshotValidationException(
                "Item snapshot payload contained no item");
        }
        return restored;
    }
}
