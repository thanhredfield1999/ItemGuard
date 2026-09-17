package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.restore.LossReason;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Disable is a window, and the item-entity handlers are inside it.
 *
 * <p>{@code stop()} exists precisely because the database is about to close: the scan checks
 * {@code stopped} and returns. The two entity handlers do not. A ground item that falls into the
 * void, or despawns, between {@code stop()} and {@code DatabaseManager.close()} still reaches
 * {@code recordLoss}, which submits to an executor that is shutting down — and a
 * {@code RejectedExecutionException} thrown out of a {@code MONITOR} handler is logged by Paper as a
 * plugin error during shutdown, on an event the plugin had already declared it was done with.
 *
 * <p>Shutdown noise is the whole defect: nothing is lost by ignoring these, because nothing can be
 * written after the connection closes anyway. What must not happen is the handlers going quiet
 * BEFORE stop, which would hide real losses for the entire life of the server.
 */
class ItemLossShutdownQuiescenceTest {

    @Test
    void aVoidRemovalAfterStopIsIgnoredQuietly() {
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemLossListener listener = listenerFor(tracking);

        listener.stop();
        listener.onGroundItemRemoved(removal(droppedItem(), EntityRemoveEvent.Cause.OUT_OF_WORLD));

        verify(tracking, never()).recordLoss(any(), any(), any(), any());
    }

    @Test
    void aDespawnAfterStopIsIgnoredQuietly() {
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemLossListener listener = listenerFor(tracking);

        listener.stop();
        listener.onGroundItemDespawn(despawn(droppedItem()));

        verify(tracking, never()).recordLoss(any(), any(), any(), any());
    }

    @Test
    void aVoidRemovalBeforeStopIsStillRecorded() {
        // The control that keeps the fix honest: a guard that silenced the handler outright would
        // pass the two tests above while losing every void loss the server ever sees.
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemLossListener listener = listenerFor(tracking);

        Item dropped = droppedItem();
        ItemStack stack = dropped.getItemStack();
        listener.onGroundItemRemoved(removal(dropped, EntityRemoveEvent.Cause.OUT_OF_WORLD));

        verify(tracking, times(1)).recordLoss(same(stack), eq(LossReason.VOID), any(), isNull());
    }

    @Test
    void aDespawnBeforeStopIsStillRecorded() {
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemLossListener listener = listenerFor(tracking);

        Item dropped = droppedItem();
        ItemStack stack = dropped.getItemStack();
        listener.onGroundItemDespawn(despawn(dropped));

        verify(tracking, times(1)).recordLoss(same(stack), any(), any(), isNull());
    }

    private ItemLossListener listenerFor(ItemTrackingService tracking) {
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);
        return new ItemLossListener(plugin);
    }

    /** A real ItemStack needs a Paper registry no offline test has; only identity matters here. */
    private Item droppedItem() {
        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(dropped.getItemStack()).thenReturn(mock(ItemStack.class));
        return dropped;
    }

    private EntityRemoveEvent removal(Item entity, EntityRemoveEvent.Cause cause) {
        EntityRemoveEvent event = mock(EntityRemoveEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getCause()).thenReturn(cause);
        return event;
    }

    private ItemDespawnEvent despawn(Item entity) {
        ItemDespawnEvent event = mock(ItemDespawnEvent.class);
        when(event.getEntity()).thenReturn(entity);
        return event;
    }
}
