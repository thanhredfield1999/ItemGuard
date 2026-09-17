package com.itemguard.listeners;

import com.itemguard.ItemGuard;
import com.itemguard.restore.LossReason;
import com.itemguard.services.ItemTrackingService;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * Behaviour pinned against the decompiled Paper 1.21.11 server jar, not against the API javadoc.
 *
 * <p>The previous round derived the {@code destroysDamagedItem} allowlist from
 * {@code paper-api}'s {@code EntityRemoveEvent.Cause} enum and from prose in the javadoc. That
 * enum is only the vocabulary; it says nothing about which causes the server actually emits for
 * an {@code ItemEntity}. Reading the shipped server bytecode
 * ({@code versions/1.21.11/paper-1.21.11.jar}, build {@code 2026-05-03}) shows three concrete
 * mismatches, each asserted below.
 *
 * <p>Evidence is recorded in {@code docs/reviews/2026-09-14-item-loss-paper-source-verification.md};
 * the exact {@code javap} commands are in that file. Nothing here starts a Paper server.
 */
class ItemLossPaperCauseContractTest {

    /**
     * {@code ItemEntity.hurtServer} — the only place an item entity dies of damage — ends with
     * {@code discard(EntityRemoveEvent$Cause.DEATH)} once {@code health <= 0}. It never passes
     * {@code DISCARD}.
     *
     * <p>{@code DISCARD} is referenced by exactly five server classes, none of which is a damage
     * path: {@code ChunkStatusTasks} (duplicate-UUID cleanup), {@code Level} (entity tick threw an
     * exception), {@code TestInstanceBlockEntity}, {@code StructureUtils} and {@code GameTestInfo}
     * (gametest scaffolding). Two of those delete a live item for reasons that have nothing to do
     * with the remembered fire/lava damage, so accepting {@code DISCARD} attributes an old damage
     * cause to an unrelated removal — exactly the false-confirmation this listener exists to avoid.
     */
    @Test
    void discardIsNotADamageDeathCauseAndMustNotConfirmDestruction() {
        assertTrue(!ItemLossPolicy.destroysDamagedItem("DISCARD"),
            "Paper emits DEATH for an item destroyed by damage; DISCARD is duplicate-UUID cleanup, "
                + "a tick-exception removal or gametest teardown, so it cannot confirm the "
                + "remembered damage destroyed the stack");
    }

    /**
     * {@code Entity.onBelowWorld()} is {@code discard(OUT_OF_WORLD)} and {@code ItemEntity} does
     * not override it — only {@code LivingEntity} does, converting it to void damage. So for an
     * item entity, falling out of the world is a removal with no preceding {@code VOID}
     * {@code EntityDamageEvent} at all.
     *
     * <p>A removal with no remembered damage must therefore still be recorded as a void loss,
     * otherwise every item that falls into the void goes unrecorded. The old design could only
     * record a loss when a damage note already existed, which for {@code OUT_OF_WORLD} never
     * happens.
     */
    @Test
    void anItemFallingOutOfTheWorldIsRecordedWithoutAnyPrecedingDamage() {
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);

        ItemStack stack = mock(ItemStack.class);
        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(UUID.randomUUID());
        when(dropped.getItemStack()).thenReturn(stack);

        ItemLossListener listener = new ItemLossListener(plugin);
        // No onGroundItemDamaged call: ItemEntity inherits Entity.onBelowWorld, which discards
        // directly and fires no VOID damage event.
        listener.onGroundItemRemoved(removal(dropped, EntityRemoveEvent.Cause.OUT_OF_WORLD));

        verify(tracking, times(1)).recordLoss(same(stack), eq(LossReason.VOID), any(), isNull());
    }

    /**
     * {@code ItemEntity.hurtServer} calls {@code ItemStack.onDestroyed} and then {@code discard}
     * without clearing {@code DATA_ITEM}, so {@code CraftItem.getItemStack()} still mirrors the
     * real stack inside the {@code EntityRemoveEvent} callback. That is what makes recording at
     * removal time possible at all — asserted here so a future Paper change that empties the stack
     * before the event breaks this test rather than silently recording nothing.
     */
    @Test
    void theStackIsStillReadableAtRemovalTimeForADeathCause() {
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);

        ItemStack stack = mock(ItemStack.class);
        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(UUID.randomUUID());
        when(dropped.getItemStack()).thenReturn(stack);

        ItemLossListener listener = new ItemLossListener(plugin);
        listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.LAVA));
        listener.onGroundItemRemoved(removal(dropped, EntityRemoveEvent.Cause.DEATH));

        verify(tracking, times(1)).recordLoss(same(stack), eq(LossReason.BURNED), any(), isNull());
    }

    /**
     * A damaged item that survives and is later removed by a chunk unload must record nothing.
     *
     * <p>{@code UNLOAD} is emitted from {@code PersistentEntitySectionManager} and Moonrise's
     * {@code PaperHooks}; the entity is serialised to the region file, so the stack still exists.
     */
    @Test
    void aDamagedItemWhoseChunkUnloadsIsNotALoss() {
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);

        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(UUID.randomUUID());
        when(dropped.getItemStack()).thenReturn(mock(ItemStack.class));

        ItemLossListener listener = new ItemLossListener(plugin);
        listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.FIRE));
        listener.onGroundItemRemoved(removal(dropped, EntityRemoveEvent.Cause.UNLOAD));

        verify(tracking, never()).recordLoss(any(), any(), any(), any());
        verify(tracking, never()).recordLossByIdentity(any(), any(), any(), any(), any());
    }

    /**
     * A damaged item merged into another stack is not destroyed: {@code ItemEntity.merge} moves the
     * count into the surviving entity and discards the emptied one with {@code MERGE}. Recording a
     * confirmed loss there would mark a stack destroyed while its contents are still on the ground.
     */
    @Test
    void aDamagedItemThatMergesIntoAnotherStackIsNotALoss() {
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);

        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(UUID.randomUUID());
        when(dropped.getItemStack()).thenReturn(mock(ItemStack.class));

        ItemLossListener listener = new ItemLossListener(plugin);
        listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.LAVA));
        listener.onGroundItemRemoved(removal(dropped, EntityRemoveEvent.Cause.MERGE));

        verify(tracking, never()).recordLoss(any(), any(), any(), any());
    }

    /**
     * {@code CraftEntity.remove()} and {@code CraftItem.remove()} discard with {@code PLUGIN}.
     * Another plugin deleting the entity is not evidence that ItemGuard's remembered fire damage
     * destroyed it, so nothing is recorded.
     */
    @Test
    void aDamagedItemRemovedByAnotherPluginIsNotAttributedToTheDamage() {
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);

        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(UUID.randomUUID());
        when(dropped.getItemStack()).thenReturn(mock(ItemStack.class));

        ItemLossListener listener = new ItemLossListener(plugin);
        listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.LAVA));
        listener.onGroundItemRemoved(removal(dropped, EntityRemoveEvent.Cause.PLUGIN));

        verify(tracking, never()).recordLoss(any(), any(), any(), any());
    }

    /**
     * {@code ItemEntity.tick} discards with {@code DESPAWN} when the age timer expires, and
     * {@code ItemDespawnEvent} already records that case. Recording again from the removal handler
     * would write the loss twice for one disappearance.
     */
    @Test
    void theDespawnTimerIsLeftToTheDespawnEventSoItIsNotRecordedTwice() {
        ItemTrackingService tracking = mock(ItemTrackingService.class);
        ItemGuard plugin = mock(ItemGuard.class);
        when(plugin.getTrackingService()).thenReturn(tracking);

        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(UUID.randomUUID());
        when(dropped.getItemStack()).thenReturn(mock(ItemStack.class));

        ItemLossListener listener = new ItemLossListener(plugin);
        listener.onGroundItemDamaged(damage(dropped, EntityDamageEvent.DamageCause.FIRE));
        listener.onGroundItemRemoved(removal(dropped, EntityRemoveEvent.Cause.DESPAWN));

        verify(tracking, never()).recordLoss(any(), any(), any(), any());
    }

    private EntityRemoveEvent removal(Item entity, EntityRemoveEvent.Cause cause) {
        EntityRemoveEvent event = mock(EntityRemoveEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getCause()).thenReturn(cause);
        return event;
    }

    private EntityDamageEvent damage(Item entity, EntityDamageEvent.DamageCause cause) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getCause()).thenReturn(cause);
        return event;
    }
}
