package com.itemguard.tracking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The physical-handle check must recognise CraftItemStack on Spigot as well as Paper.
 *
 * <p>Paper relocated CraftBukkit out of the versioned package; Spigot did not:
 *
 * <pre>
 * Paper    org.bukkit.craftbukkit.inventory.CraftItemStack
 * Spigot   org.bukkit.craftbukkit.v1_21_R3.inventory.CraftItemStack
 * </pre>
 *
 * <p>The original check compared the class name against Paper's exact string, so on Spigot
 * {@code extract} always returned null. Every caller treats null as "cannot verify" and
 * returns false without logging — {@code ItemTrackingService:428} is the clearest example —
 * so on a real Spigot 1.21.4 server the plugin enabled cleanly, reported no errors, and
 * silently tagged nothing at all. The fixture only caught it as a probe timeout
 * ({@code LITE_PROBE_FAIL sweep-identity-deadline}), which looks nothing like the real cause.
 *
 * <p>A silent no-op is the worst failure mode for this plugin: an operator would believe
 * items were being tracked while the database stayed empty.
 */
class CraftItemStackPhysicalHandleNameTest {

    @Test
    @DisplayName("Paper's relocated class name is recognised")
    void acceptsPaperClassName() {
        assertTrue(CraftItemStackPhysicalHandle.isCraftItemStackClassName(
            "org.bukkit.craftbukkit.inventory.CraftItemStack"));
    }

    @Test
    @DisplayName("Spigot's versioned class name is recognised")
    void acceptsSpigotVersionedClassName() {
        assertTrue(CraftItemStackPhysicalHandle.isCraftItemStackClassName(
            "org.bukkit.craftbukkit.v1_21_R3.inventory.CraftItemStack"));
        // Older and future mappings use the same shape; the revision must not be hardcoded.
        assertTrue(CraftItemStackPhysicalHandle.isCraftItemStackClassName(
            "org.bukkit.craftbukkit.v1_20_R4.inventory.CraftItemStack"));
        assertTrue(CraftItemStackPhysicalHandle.isCraftItemStackClassName(
            "org.bukkit.craftbukkit.v1_22_R1.inventory.CraftItemStack"));
    }

    @Test
    @DisplayName("unrelated classes are still rejected")
    void rejectsEverythingElse() {
        assertFalse(CraftItemStackPhysicalHandle.isCraftItemStackClassName(null));
        assertFalse(CraftItemStackPhysicalHandle.isCraftItemStackClassName(
            "org.bukkit.inventory.ItemStack"));
        // A plain Bukkit ItemStack has no NMS handle; accepting it would let the caller
        // believe it had verified physical identity when it had not.
        assertFalse(CraftItemStackPhysicalHandle.isCraftItemStackClassName(
            "org.bukkit.craftbukkit.v1_21_R3.inventory.CraftMetaItem"));
        assertFalse(CraftItemStackPhysicalHandle.isCraftItemStackClassName(
            "com.example.evil.craftbukkit.inventory.CraftItemStack"));
        assertFalse(CraftItemStackPhysicalHandle.isCraftItemStackClassName(
            "org.bukkit.craftbukkit.inventory.CraftItemStackFake"));
    }

    /** Stand-in for Spigot's CraftItemStack: the handle field is package-private there. */
    static final class PackagePrivateHandleHolder {
        Object handle = "nms-stack";
    }

    /** Stand-in for Paper's: same field, public. */
    public static final class PublicHandleHolder {
        public Object handle = "nms-stack";
    }

    @Test
    @DisplayName("a package-private handle field is still readable (Spigot's shape)")
    void readsPackagePrivateHandleField() throws Exception {
        // Spigot declares `handle` package-private; getField() cannot see it, which is why
        // extract() returned null on every Spigot server and tagging silently never happened.
        var field = PackagePrivateHandleHolder.class.getDeclaredField("handle");
        field.setAccessible(true);
        assertTrue("nms-stack".equals(field.get(new PackagePrivateHandleHolder())));

        // The public-only lookup that used to be in extract() fails on this shape.
        boolean publicLookupFails = false;
        try {
            PackagePrivateHandleHolder.class.getField("handle");
        } catch (NoSuchFieldException expected) {
            publicLookupFails = true;
        }
        assertTrue(publicLookupFails,
            "if getField() can see a package-private field, this test no longer proves anything");

        // Paper's shape must keep working through the same path.
        var publicField = PublicHandleHolder.class.getDeclaredField("handle");
        publicField.setAccessible(true);
        assertTrue("nms-stack".equals(publicField.get(new PublicHandleHolder())));
    }
}
