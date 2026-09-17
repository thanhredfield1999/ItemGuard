package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The remembered-damage map must not grow without bound.
 *
 * <p>Previously recorded as a known limit rather than tested: {@code pendingDamage} was only ever
 * drained by a removal, so every damaged item entity that never got removed left an entry behind.
 * That is not hypothetical on a real server — reading the shipped Paper 1.21.11 bytecode,
 * {@code ItemEntity.hurtServer} writes {@code health -= damage} and only discards when health
 * reaches zero, so a stack sitting in a one-damage-per-tick fire that a player extinguishes
 * survives with a note left in the map forever. Fire damage repeats on a tick timer, so the same
 * entity also rewrites its note many times per second.
 *
 * <p>An unbounded map on a listener that is fed by a tick-rate event is a memory leak in the
 * plugin's own process. The bound has to be enforced by the listener, because nothing else ever
 * calls it back for an entity that simply stays alive.
 */
class ItemLossPendingDamageBoundTest {

    /** Comfortably more than any plausible number of simultaneously-burning tracked drops. */
    private static final int FLOOD = 20_000;

    @Test
    void damageNotesForItemsThatNeverGetRemovedDoNotGrowWithoutBound() {
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);

        ItemLossListener listener = new ItemLossListener(plugin);

        for (int i = 0; i < FLOOD; i++) {
            Item dropped = mock(Item.class);
            when(dropped.getUniqueId()).thenReturn(UUID.randomUUID());
            // Survives: no removal event ever arrives for this entity.
            listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.LAVA));
        }

        int retained = listener.pendingDamageSize();
        assertTrue(retained < FLOOD,
            "every damaged-but-surviving item left a permanent entry: " + retained + " of " + FLOOD);
        assertTrue(retained <= ItemLossListener.MAX_PENDING_DAMAGE,
            "pending damage notes exceeded the declared bound: " + retained
                + " > " + ItemLossListener.MAX_PENDING_DAMAGE);
    }

    @Test
    void theMostRecentlyDamagedItemIsTheOneStillRemembered() {
        // Eviction has to drop the oldest note, not the newest. The item that was damaged a moment
        // ago is the one most likely to be about to burn up; forgetting it instead would lose the
        // real destruction record while keeping a stale one.
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);

        ItemLossListener listener = new ItemLossListener(plugin);

        UUID newest = null;
        for (int i = 0; i < ItemLossListener.MAX_PENDING_DAMAGE + 500; i++) {
            Item dropped = mock(Item.class);
            newest = UUID.randomUUID();
            when(dropped.getUniqueId()).thenReturn(newest);
            when(dropped.getItemStack()).thenReturn(mock(ItemStack.class));
            listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.FIRE));
        }

        assertTrue(listener.hasPendingDamage(newest),
            "the most recently damaged item was evicted, so its destruction would go unrecorded");
    }

    private EntityDamageEvent damage(Item entity, EntityDamageEvent.DamageCause cause) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getCause()).thenReturn(cause);
        return event;
    }
}
