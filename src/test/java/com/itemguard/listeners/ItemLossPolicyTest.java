package com.itemguard.listeners;

import com.itemguard.restore.LossReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Maps a Bukkit removal event onto a recorded loss reason.
 *
 * <p>Found by testing: nothing was listening for destruction at all, so {@code /clear} removed a
 * tracked item and the records still showed it as held. The reason enum existed but no event ever
 * produced one.
 */
class ItemLossPolicyTest {

    @Test void fireAndLavaDamageIsBurned() {
        assertEquals(LossReason.BURNED, ItemLossPolicy.forEntityDamage("FIRE"));
        assertEquals(LossReason.BURNED, ItemLossPolicy.forEntityDamage("FIRE_TICK"));
        assertEquals(LossReason.BURNED, ItemLossPolicy.forEntityDamage("LAVA"));
    }

    @Test void theVoidIsItsOwnReason() {
        assertEquals(LossReason.VOID, ItemLossPolicy.forEntityDamage("VOID"));
    }

    @Test void otherDamageDoesNotDestroyAGroundItem() {
        // An explosion scatters items, it does not delete the identity.
        assertNull(ItemLossPolicy.forEntityDamage("ENTITY_EXPLOSION"));
        assertNull(ItemLossPolicy.forEntityDamage("FALL"));
        assertNull(ItemLossPolicy.forEntityDamage(null));
    }

    @Test void theDespawnTimerIsItsOwnReason() {
        assertEquals(LossReason.DESPAWNED, ItemLossPolicy.forDespawn());
    }

    @Test void inventoryRemovalByACommandIsCleared() {
        assertEquals(LossReason.CLEARED, ItemLossPolicy.forInventoryRemoval());
    }

    @Test void everyMappedReasonConfirmsDestructionSoARestoreCanBeJudged() {
        // A reason that does not confirm destruction would leave the item un-restorable.
        assertTrue(ItemLossPolicy.forDespawn().confirmsDestruction());
        assertTrue(ItemLossPolicy.forInventoryRemoval().confirmsDestruction());
        assertTrue(ItemLossPolicy.forEntityDamage("LAVA").confirmsDestruction());
    }

    @Test void onlyARemovalThatEndsTheEntityConfirmsDamageDestroyedIt() {
        // Damage is survivable, so the removal cause is what decides. Verified against the shipped
        // Paper 1.21.11 server bytecode: ItemEntity.hurtServer discards with DEATH when health
        // reaches zero, and that is the only damage path that ends an item entity.
        assertTrue(ItemLossPolicy.destroysDamagedItem("DEATH"));
    }

    @Test void aRemovalThatLeavesTheItemExistingIsNotDestruction() {
        // Each of these means the stack is still somewhere: an inventory, another stack, or a
        // chunk on disk. Confirming a loss for any of them would make a live item restorable.
        assertFalse(ItemLossPolicy.destroysDamagedItem("PICKUP"));
        assertFalse(ItemLossPolicy.destroysDamagedItem("MERGE"));
        assertFalse(ItemLossPolicy.destroysDamagedItem("UNLOAD"));
        assertFalse(ItemLossPolicy.destroysDamagedItem("PLUGIN"));
        assertFalse(ItemLossPolicy.destroysDamagedItem("DESPAWN"));
        assertFalse(ItemLossPolicy.destroysDamagedItem(null));
    }

    @Test void discardIsNotEmittedByAnyDamagePathSoItCannotConfirmABurn() {
        // DISCARD appears in five server classes, none of them a damage path: duplicate-UUID chunk
        // cleanup, removal of an entity whose tick threw, and gametest teardown. Charging a
        // remembered fire note to one of those would attribute an old cause to an unrelated removal.
        assertFalse(ItemLossPolicy.destroysDamagedItem("DISCARD"));
    }

    @Test void theVoidIsProvenByRemovalAloneBecauseAnItemNeverTakesVoidDamage() {
        // ItemEntity does not override Entity.onBelowWorld, which is discard(OUT_OF_WORLD). Only
        // LivingEntity converts that into damage, so an item produces no VOID EntityDamageEvent and
        // there is never a note to confirm.
        assertEquals(LossReason.VOID, ItemLossPolicy.forRemovalWithoutDamage("OUT_OF_WORLD"));
        assertFalse(ItemLossPolicy.destroysDamagedItem("OUT_OF_WORLD"));
    }

    @Test void noOtherRemovalCauseStandsOnItsOwnAsALoss() {
        // Fail closed: only OUT_OF_WORLD is self-evident. Everything else needs a damage note, and
        // an unknown future cause must never become a standalone confirmed loss.
        assertNull(ItemLossPolicy.forRemovalWithoutDamage("DEATH"));
        assertNull(ItemLossPolicy.forRemovalWithoutDamage("PICKUP"));
        assertNull(ItemLossPolicy.forRemovalWithoutDamage("UNLOAD"));
        assertNull(ItemLossPolicy.forRemovalWithoutDamage("DISCARD"));
        assertNull(ItemLossPolicy.forRemovalWithoutDamage("SOME_FUTURE_CAUSE"));
        assertNull(ItemLossPolicy.forRemovalWithoutDamage(null));
    }

    @Test void anUnknownRemovalCauseIsNotTreatedAsDestruction() {
        // Fail closed: a cause added by a future Paper version must not silently confirm a loss.
        assertFalse(ItemLossPolicy.destroysDamagedItem("SOME_FUTURE_CAUSE"));
    }
}
