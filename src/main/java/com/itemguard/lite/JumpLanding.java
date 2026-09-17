package com.itemguard.lite;

import java.util.List;

/**
 * Where a jump lands: one block in front of the container, centred, looking at it.
 *
 * <p>The container-facing side is preferred. If terrain changed and it is blocked, the remaining
 * three sides are checked in a stable order so an administrator still lands next to the container.
 *
 * @param x     block centre, so the view is straight rather than skewed
 * @param y     the container's own level
 * @param z     block centre
 * @param yaw   looking back at the container
 * @param pitch tilted slightly down so the container is in view, not the horizon
 */
public record JumpLanding(double x, double y, double z, float yaw, float pitch) {

    /** Looking a little downward, so the container fills the view instead of the skyline. */
    private static final float PITCH = 20.0f;

    public static JumpLanding inFrontOf(int blockX, int blockY, int blockZ, BlockFacing facing) {
        BlockFacing front = facing == null ? BlockFacing.NORTH : facing;
        return new JumpLanding(
            blockX + front.dx() + 0.5,
            blockY,
            blockZ + front.dz() + 0.5,
            front.yawLookingBack(),
            PITCH
        );
    }

    /**
     * Ordered candidate positions around the same container. The ordinary front is retained first;
     * if terrain changed, an operator still gets the next safe side rather than a dead-end refusal.
     */
    public static List<JumpLanding> around(int blockX, int blockY, int blockZ, BlockFacing facing) {
        BlockFacing front = facing == null ? BlockFacing.NORTH : facing;
        return List.of(
            inFrontOf(blockX, blockY, blockZ, front),
            inFrontOf(blockX, blockY, blockZ, front.right()),
            inFrontOf(blockX, blockY, blockZ, front.left()),
            inFrontOf(blockX, blockY, blockZ, front.opposite())
        );
    }
}
