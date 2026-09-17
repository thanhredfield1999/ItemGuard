package com.itemguard.lite;

import java.util.Locale;

/**
 * The horizontal side a container faces.
 *
 * <p>{@code yawLookingBack} is the yaw that turns a player standing on the front block back toward
 * the container. Verified against the geometry in {@code JumpLandingTest}: standing north of a chest
 * means looking south, which is yaw 0 in Minecraft's convention.
 */
public enum BlockFacing {

    NORTH(0, -1, 0.0f),
    SOUTH(0, 1, 180.0f),
    WEST(-1, 0, 270.0f),
    EAST(1, 0, 90.0f);

    private final int dx;
    private final int dz;
    private final float yawLookingBack;

    BlockFacing(int dx, int dz, float yawLookingBack) {
        this.dx = dx;
        this.dz = dz;
        this.yawLookingBack = yawLookingBack;
    }

    /** Vertical or unrecognised faces have no usable front, so they read as north. */
    public static BlockFacing fromName(String name) {
        if (name == null || name.isBlank()) {
            return NORTH;
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "SOUTH" -> SOUTH;
            case "WEST" -> WEST;
            case "EAST" -> EAST;
            default -> NORTH;
        };
    }

    int dx() {
        return dx;
    }

    int dz() {
        return dz;
    }

    /** The yaw that looks from the front block back at the container. */
    float yawLookingBack() {
        return yawLookingBack;
    }

    /** The next horizontal side when scanning clockwise around a container. */
    BlockFacing right() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
        };
    }

    BlockFacing left() {
        return right().right().right();
    }

    BlockFacing opposite() {
        return right().right();
    }
}
