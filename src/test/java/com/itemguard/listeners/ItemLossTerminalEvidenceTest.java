package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.restore.LossReason;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
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
 * A damage cause is not proof that a tracked ground item was destroyed.
 *
 * <p>Observed in source: {@code onGroundItemDamaged} wrote a {@code BURNED}/{@code VOID} loss row the
 * moment fire, lava or void damage arrived, without ever checking whether the item entity actually
 * stopped existing. An item entity has health (default max 5) and survives glancing damage, and the
 * {@code OUT_OF_WORLD} removal cause is documented as applying only to entities removed immediately
 * because "some entities get damage instead".
 *
 * <p>This is not a cosmetic mislabel. Every reason mapped here reports
 * {@link LossReason#confirmsDestruction()}, and a confirmed-destroyed identity is what a restore
 * decision acts on. Writing that row for an item still lying on the ground is a duplication path:
 * the item exists AND the records say it was destroyed.
 */
class ItemLossTerminalEvidenceTest {

    @Test
    void lavaDamageAloneDoesNotRecordALossWhileTheItemStillExists() {
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);

        Item dropped = mock(Item.class);
        // A real ItemStack needs a Paper registry that offline tests do not have; the stack's
        // contents are irrelevant here, only whether a loss is recorded for it.
        when(dropped.getItemStack()).thenReturn(mock(ItemStack.class));
        when(dropped.isValid()).thenReturn(true);

        ItemLossListener listener = new ItemLossListener(plugin);
        listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.LAVA));

        // Untyped any() throughout: a typed any(Location.class) does not match the null location a
        // mocked entity returns, which would make this assertion pass without proving anything.
        verify(tracking, never()).recordLoss(any(), any(), any(), any());
        verify(tracking, never()).recordLossByIdentity(any(), any(), any(), any(), any());
    }

    @Test
    void anItemThatBurnsUpIsStillRecordedAsBurnedWhenItLeavesTheWorld() {
        // The fix must not silence the real loss: the whole point of the listener is that an admin
        // can see why a tracked item stopped existing.
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);

        ItemStack stack = mock(ItemStack.class);
        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(dropped.getItemStack()).thenReturn(stack);

        ItemLossListener listener = new ItemLossListener(plugin);
        listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.LAVA));
        // DEATH, not DISCARD: ItemEntity.hurtServer discards with DEATH once health reaches zero.
        listener.onGroundItemRemoved(removal(dropped, EntityRemoveEvent.Cause.DEATH));

        verify(tracking, times(1))
            .recordLoss(same(stack), eq(LossReason.BURNED), any(), isNull());
    }

    @Test
    void anItemDamagedThenPickedUpIsNeverRecordedAsLost() {
        // Surviving fire and being collected is the exact case the old code got wrong. PICKUP means
        // the item is in somebody's inventory, so confirming destruction here would be a dupe path.
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);

        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(dropped.getItemStack()).thenReturn(mock(ItemStack.class));

        ItemLossListener listener = new ItemLossListener(plugin);
        listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.FIRE));
        listener.onGroundItemRemoved(removal(dropped, EntityRemoveEvent.Cause.PICKUP));

        verify(tracking, never()).recordLoss(any(), any(), any(), any());
        verify(tracking, never()).recordLossByIdentity(any(), any(), any(), any(), any());
    }

    @Test
    void aRememberedDamageIsNotHeldAgainstALaterUnrelatedRemoval() {
        // The pending note must be consumed by the removal that follows it. If it lingered, a
        // recycled entity id or a later benign removal could inherit a destruction reason.
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);

        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(dropped.getItemStack()).thenReturn(mock(ItemStack.class));

        ItemLossListener listener = new ItemLossListener(plugin);
        listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.LAVA));
        listener.onGroundItemRemoved(removal(dropped, EntityRemoveEvent.Cause.PICKUP));
        listener.onGroundItemRemoved(removal(dropped, EntityRemoveEvent.Cause.DEATH));

        verify(tracking, never()).recordLoss(any(), any(), any(), any());
    }

    @Test
    void laterUnclassifiedDamageCannotInheritAnOldBurnReason() {
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);
        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(dropped.getItemStack()).thenReturn(mock(ItemStack.class));
        ItemLossListener listener = new ItemLossListener(plugin);
        listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.FIRE));
        listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION));
        listener.onGroundItemRemoved(removal(dropped, EntityRemoveEvent.Cause.DEATH));
        verify(tracking, never()).recordLoss(any(), any(), any(), any());
    }

    private EntityRemoveEvent removal(Item entity, EntityRemoveEvent.Cause cause) {
        EntityRemoveEvent event = mock(EntityRemoveEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getCause()).thenReturn(cause);
        return event;
    }

    /**
     * Constructing a real {@link EntityDamageEvent} builds a {@code DamageSource}, which needs a
     * Paper registry no offline test has. Only the entity and the cause matter to the listener.
     */
    private EntityDamageEvent damage(Item entity, EntityDamageEvent.DamageCause cause) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getCause()).thenReturn(cause);
        return event;
    }
}
