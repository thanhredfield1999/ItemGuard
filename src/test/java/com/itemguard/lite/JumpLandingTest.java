package com.itemguard.lite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a jump lands, in block terms.
 *
 * <p>The first version picked whichever of four sides happened to be walkable, so two jumps to the
 * same chest could land differently and face the wrong way. This computes one deterministic landing:
 * one block in front of the chest, looking straight at it.
 */
class JumpLandingTest {

    /** A chest at (10, 64, 20) facing north; the block in front is to the north, z - 1. */
    @Test void landsOneBlockInFrontOfTheChest() {
        JumpLanding landing = JumpLanding.inFrontOf(10, 64, 20, BlockFacing.NORTH);
        assertEquals(10.5, landing.x(), 1e-9);
        assertEquals(64.0, landing.y(), 1e-9);
        assertEquals(19.5, landing.z(), 1e-9, "one block north of the chest");
    }

    @Test void eachFacingPutsTheLandingOnThatSide() {
        assertEquals(21.5, JumpLanding.inFrontOf(10, 64, 20, BlockFacing.SOUTH).z(), 1e-9);
        assertEquals(9.5, JumpLanding.inFrontOf(10, 64, 20, BlockFacing.WEST).x(), 1e-9);
        assertEquals(11.5, JumpLanding.inFrontOf(10, 64, 20, BlockFacing.EAST).x(), 1e-9);
    }

    @Test void theLandingIsCentredOnItsBlockSoTheViewIsStraight() {
        JumpLanding landing = JumpLanding.inFrontOf(10, 64, 20, BlockFacing.NORTH);
        assertEquals(0.5, landing.x() - Math.floor(landing.x()), 1e-9);
        assertEquals(0.5, Math.abs(landing.z() - Math.floor(landing.z())), 1e-9);
    }

    @Test void theViewLooksAtTheChestNotAwayFromIt() {
        // Minecraft's convention: yaw 0 faces south. Standing north of a chest means looking south.
        assertEquals(0.0f, JumpLanding.inFrontOf(10, 64, 20, BlockFacing.NORTH).yaw(), 1e-4);
        assertEquals(180.0f, JumpLanding.inFrontOf(10, 64, 20, BlockFacing.SOUTH).yaw(), 1e-4);
        assertEquals(270.0f, JumpLanding.inFrontOf(10, 64, 20, BlockFacing.WEST).yaw(), 1e-4);
        assertEquals(90.0f, JumpLanding.inFrontOf(10, 64, 20, BlockFacing.EAST).yaw(), 1e-4);
    }

    @Test void theYawPointsFromTheLandingBackTowardTheChest() {
        // Derived from the geometry rather than from the table above, so a wrong table cannot agree
        // with itself. This test is what caught an attempt to "fix" the correct values.
        for (BlockFacing facing : BlockFacing.values()) {
            JumpLanding landing = JumpLanding.inFrontOf(10, 64, 20, facing);
            double dx = (10 + 0.5) - landing.x();
            double dz = (20 + 0.5) - landing.z();
            double expected = Math.toDegrees(Math.atan2(-dx, dz));
            double error = Math.abs(((expected - landing.yaw() + 540) % 360) - 180);
            assertTrue(error < 1.0,
                facing + " should look at the chest, off by " + error + " degrees");
        }
    }

    @Test void thePitchIsSlightlyDownSoTheChestIsInView() {
        assertTrue(JumpLanding.inFrontOf(10, 64, 20, BlockFacing.NORTH).pitch() > 0,
            "looking a little down at the chest, not at the horizon");
        assertTrue(JumpLanding.inFrontOf(10, 64, 20, BlockFacing.NORTH).pitch() <= 30f);
    }

    @Test void theSameChestAlwaysGivesTheSameLanding() {
        // Determinism is the whole point: two jumps must not land in different places.
        JumpLanding first = JumpLanding.inFrontOf(-5, 12, 300, BlockFacing.EAST);
        JumpLanding second = JumpLanding.inFrontOf(-5, 12, 300, BlockFacing.EAST);
        assertEquals(first, second);
    }

    @Test void fallbackCandidatesCoverEverySideWithoutMovingAwayFromTheContainer() {
        var candidates = JumpLanding.around(10, 64, 20, BlockFacing.NORTH);
        assertEquals(4, candidates.size());
        assertEquals(JumpLanding.inFrontOf(10, 64, 20, BlockFacing.NORTH), candidates.get(0));
        assertEquals(JumpLanding.inFrontOf(10, 64, 20, BlockFacing.EAST), candidates.get(1));
        assertEquals(JumpLanding.inFrontOf(10, 64, 20, BlockFacing.WEST), candidates.get(2));
        assertEquals(JumpLanding.inFrontOf(10, 64, 20, BlockFacing.SOUTH), candidates.get(3));
        assertEquals(4, candidates.stream().distinct().count(), "each side may be the only safe one");
    }

    @Test void anUnknownFacingFallsBackToNorthRatherThanFailing() {
        assertEquals(JumpLanding.inFrontOf(10, 64, 20, BlockFacing.NORTH),
            JumpLanding.inFrontOf(10, 64, 20, null));
        assertEquals(JumpLanding.around(10, 64, 20, BlockFacing.NORTH),
            JumpLanding.around(10, 64, 20, null));
    }

    @Test void facingIsReadFromABlockFaceName() {
        assertEquals(BlockFacing.NORTH, BlockFacing.fromName("NORTH"));
        assertEquals(BlockFacing.SOUTH, BlockFacing.fromName("south"));
        assertEquals(BlockFacing.NORTH, BlockFacing.fromName("UP"),
            "a vertical face has no usable front, so treat it as north");
        assertEquals(BlockFacing.NORTH, BlockFacing.fromName(null));
    }
}
