package com.itemguard.tracking;

import org.bukkit.inventory.ItemStack;

public final class CraftItemStackPhysicalHandle {

    private CraftItemStackPhysicalHandle() {
    }

    /**
     * Whether a class name is CraftBukkit's own ItemStack implementation.
     *
     * <p>Two shapes exist in the wild and both are legitimate:
     *
     * <pre>
     * Paper / Purpur   org.bukkit.craftbukkit.inventory.CraftItemStack
     * Spigot           org.bukkit.craftbukkit.v1_21_R3.inventory.CraftItemStack
     * </pre>
     *
     * <p>Paper dropped the versioned package; Spigot kept it, and the revision changes with
     * every Minecraft release, so it cannot be hardcoded. Matching only Paper's string made
     * {@code extract} return null on every Spigot server — and because callers read null as
     * "cannot verify" and return false without logging, the plugin enabled cleanly and then
     * tracked nothing at all.
     *
     * <p>The match stays anchored at both ends: the name must start with the real CraftBukkit
     * package and end with exactly this class, so a lookalike from another plugin cannot pass
     * itself off as a server handle.
     */
    static boolean isCraftItemStackClassName(String className) {
        if (className == null || !className.startsWith("org.bukkit.craftbukkit.")) {
            return false;
        }
        String remainder = className.substring("org.bukkit.craftbukkit.".length());
        if (remainder.equals("inventory.CraftItemStack")) {
            return true;
        }
        // Versioned form: exactly one package segment (the mapping revision) in between.
        int separator = remainder.indexOf('.');
        if (separator <= 0) {
            return false;
        }
        return remainder.substring(separator + 1).equals("inventory.CraftItemStack");
    }

    public static Object extract(ItemStack item) {
        if (item == null || !isCraftItemStackClassName(item.getClass().getName())) {
            return null;
        }
        try {
            // Spigot declares `handle` package-private; Paper exposes it. getField() only sees
            // public members, so on Spigot it threw NoSuchFieldException, extract returned null,
            // and every caller read that as "cannot verify" and silently skipped tagging —
            // the plugin enabled cleanly and tracked nothing. getDeclaredField sees both.
            var handleField = item.getClass().getDeclaredField("handle");
            handleField.setAccessible(true);
            return handleField.get(item);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // RuntimeException covers SecurityException and InaccessibleObjectException under
            // a strict module setup; either way the answer is "cannot verify".
            return null;
        }
    }
}
