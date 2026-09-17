package com.itemguard.listeners;

import com.itemguard.restore.LossReason;

import java.util.Locale;

/**
 * Maps a removal event onto the reason written to history.
 *
 * <p>Only causes that genuinely end an item's existence map to a reason. Damage that merely moves or
 * scatters an item returns null, because recording it as destroyed would let an admin restore an item
 * that still exists.
 */
public final class ItemLossPolicy {

    private ItemLossPolicy() {
    }

    /**
     * @param damageCauseName Bukkit {@code DamageCause} name
     * @return the loss reason, or null when this damage does not destroy the item
     */
    public static LossReason forEntityDamage(String damageCauseName) {
        if (damageCauseName == null) {
            return null;
        }
        return switch (damageCauseName.trim().toUpperCase(Locale.ROOT)) {
            case "FIRE", "FIRE_TICK", "LAVA" -> LossReason.BURNED;
            case "VOID" -> LossReason.VOID;
            default -> null;
        };
    }

    /** The ground despawn timer expired. */
    public static LossReason forDespawn() {
        return LossReason.DESPAWNED;
    }

    /**
     * Whether an entity removal is consistent with damage having destroyed the item.
     *
     * <p>Deliberately an allowlist. Most removal causes mean the item still exists somewhere — it
     * was picked up, it merged into another stack, its chunk unloaded — and treating one of those
     * as destruction would confirm a loss for an item that can still be found. Recording nothing is
     * recoverable; a false confirmed loss is a duplication path.
     *
     * <p>Narrowed to {@code DEATH} alone after reading the shipped Paper 1.21.11 server bytecode
     * rather than the API enum. {@code ItemEntity.hurtServer} is the only place damage ends an item
     * entity, and it discards with {@code DEATH}. {@code DISCARD} is never emitted from a damage
     * path: the five server classes that pass it are duplicate-UUID chunk cleanup, the removal of
     * an entity whose tick threw, and gametest teardown. Accepting it would let an old fire note be
     * charged to a removal that had nothing to do with the fire.
     *
     * <p>{@code OUT_OF_WORLD} is deliberately excluded here too, but for the opposite reason: an
     * item entity never receives void damage at all ({@code ItemEntity} does not override
     * {@code Entity.onBelowWorld}, which discards immediately), so there is no damage note to
     * confirm. That case is handled directly at removal time instead.
     *
     * @param removeCauseName Bukkit {@code EntityRemoveEvent.Cause} name
     */
    public static boolean destroysDamagedItem(String removeCauseName) {
        if (removeCauseName == null) {
            return false;
        }
        return switch (removeCauseName.trim().toUpperCase(Locale.ROOT)) {
            // The item entity's health hit zero and Paper discarded it with this cause.
            case "DEATH" -> true;
            default -> false;
        };
    }

    /**
     * Whether a removal is by itself proof that the item fell out of the world.
     *
     * <p>{@code Entity.onBelowWorld()} is {@code discard(OUT_OF_WORLD)} and {@code ItemEntity} does
     * not override it — only {@code LivingEntity} does, turning it into void damage. So unlike fire
     * and lava, the void produces no {@code EntityDamageEvent} for an item at all, and waiting for
     * one would mean every item lost to the void goes unrecorded. The removal cause is the whole
     * signal, and it is unambiguous: the entity is gone and it went below the world.
     *
     * @param removeCauseName Bukkit {@code EntityRemoveEvent.Cause} name
     */
    public static LossReason forRemovalWithoutDamage(String removeCauseName) {
        if (removeCauseName == null) {
            return null;
        }
        return switch (removeCauseName.trim().toUpperCase(Locale.ROOT)) {
            case "OUT_OF_WORLD" -> LossReason.VOID;
            default -> null;
        };
    }

    /** Removed from an inventory by a command or another plugin, such as {@code /clear}. */
    public static LossReason forInventoryRemoval() {
        return LossReason.CLEARED;
    }
}
