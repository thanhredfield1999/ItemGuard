package com.itemguard.lite;

/**
 * One attempt to jump to a timeline row's position.
 *
 * @param action        the row's recorded action
 * @param hasPosition   whether a position was recorded at all
 * @param worldLoaded   whether the recorded world is loaded
 * @param hasSafeSpot   whether a safe standing position next to the container was found
 * @param maySeeRow     whether this viewer may see the row's details at all
 * @param hasPermission whether the viewer holds the teleport permission
 */
public record JumpRequest(
    String action,
    boolean hasPosition,
    boolean worldLoaded,
    boolean hasSafeSpot,
    boolean maySeeRow,
    boolean hasPermission
) {}
