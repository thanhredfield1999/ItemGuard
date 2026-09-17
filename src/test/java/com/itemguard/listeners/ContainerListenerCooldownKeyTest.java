package com.itemguard.listeners;

import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H3 (review 2026-09-16): the hopper/container cooldown was keyed on {@code holder.toString()}.
 *
 * <p>A block container's holder prints its coordinates, so that case happened to work. Everything
 * else — a minecart's entity holder, any holder falling back to {@code Object.toString()} — printed
 * an identity hash that changes with each snapshot. Those entries never matched again, so the
 * cooldown silently never suppressed anything, and each one stayed in the map for the life of the
 * process. The map is now bounded and the key is derived from identity that does not change.
 */
class ContainerListenerCooldownKeyTest {

    @Test
    void twoSnapshotsOfTheSameBlockShareOneKey() {
        assertEquals(key(block(10, 64, -7)), key(block(10, 64, -7)));
    }

    @Test
    void differentBlocksDoNotShareAKey() {
        assertNotEquals(key(block(10, 64, -7)), key(block(11, 64, -7)));
    }

    @Test
    void twoHoldersOfTheSameEntityShareOneKey() {
        UUID id = UUID.randomUUID();
        assertEquals(key(entity(id)), key(entity(id)));
    }

    @Test
    void theEntityKeyNamesTheEntityAndNotItsIdentityHash() {
        UUID id = UUID.randomUUID();
        assertTrue(key(entity(id)).contains(id.toString()), key(entity(id)));
    }

    @Test
    void anUnknownHolderStillGetsSomeKeyRatherThanFailingTheEvent() {
        assertNotEquals(
            ContainerListener.cooldownKey(new Object()),
            ContainerListener.cooldownKey(new Object())
        );
    }

    /**
     * M2 (review 2026-09-17). The most common container there is was the one case the key got
     * wrong: a double chest's holder is a {@code DoubleChest}, not a {@code BlockState}, so it fell
     * to the identity-hash branch and the cooldown never fired for it.
     */
    @Test
    void twoSnapshotsOfTheSameDoubleChestShareOneKey() {
        assertEquals(key(doubleChest(4, 63, 9)), key(doubleChest(4, 63, 9)));
    }

    @Test
    void aDoubleChestKeyIsDerivedFromItsLocationNotItsIdentityHash() {
        assertEquals("DOUBLE:" + WORLD_ID + ":4:63:9", key(doubleChest(4, 63, 9)));
    }

    @Test
    void twoDifferentDoubleChestsDoNotShareAKey() {
        assertNotEquals(key(doubleChest(4, 63, 9)), key(doubleChest(4, 63, 10)));
    }

    private static String key(Object holder) {
        return ContainerListener.cooldownKey(holder);
    }

    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static org.bukkit.block.DoubleChest doubleChest(int x, int y, int z) {
        org.bukkit.block.DoubleChest holder = mock(org.bukkit.block.DoubleChest.class);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(holder.getLocation()).thenReturn(new org.bukkit.Location(world, x, y, z));
        return holder;
    }

    private static BlockState block(int x, int y, int z) {
        BlockState state = mock(BlockState.class);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        when(state.getWorld()).thenReturn(world);
        when(state.getX()).thenReturn(x);
        when(state.getY()).thenReturn(y);
        when(state.getZ()).thenReturn(z);
        return state;
    }

    private static Entity entity(UUID id) {
        Entity entity = mock(Entity.class);
        when(entity.getUniqueId()).thenReturn(id);
        return entity;
    }
}
