package com.itemguard.lite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which icon a timeline row shows.
 *
 * <p>Corrected after testing: a row saying the item was put into a chest must show a
 * <em>chest</em>, and an ender chest row must show an <em>ender chest</em>. The earlier version
 * showed the item itself, which answered the wrong question — the row's subject is the place, not
 * the thing.
 */
class TimelineIconPolicyTest {

    @Test void aChestRowShowsAChest() {
        assertEquals(TimelineIcon.CHEST, TimelineIconPolicy.iconFor("CONTAINER_PUT", true, true));
        assertEquals(TimelineIcon.CHEST, TimelineIconPolicy.iconFor("CONTAINER_TAKE", true, true));
    }

    @Test void anEnderChestRowShowsAnEnderChest() {
        assertEquals(TimelineIcon.ENDER_CHEST,
            TimelineIconPolicy.iconFor("ENDERCHEST_PUT", true, true));
        assertEquals(TimelineIcon.ENDER_CHEST,
            TimelineIconPolicy.iconFor("ENDERCHEST_TAKE", true, true));
    }

    @Test void aCarriedBoxRowShowsAShulkerBox() {
        assertEquals(TimelineIcon.SHULKER_BOX,
            TimelineIconPolicy.iconFor("CARRIED_CONTAINER_PUT", true, true));
        assertEquals(TimelineIcon.SHULKER_BOX,
            TimelineIconPolicy.iconFor("CARRIED_CONTAINER_TAKE", true, true));
    }

    @Test void aPlacedShulkerRowAlsoShowsAShulkerBoxRatherThanAChest() {
        assertEquals(TimelineIcon.SHULKER_BOX,
            TimelineIconPolicy.iconFor("SHULKER_PUT", true, true));
        assertEquals(TimelineIcon.SHULKER_BOX,
            TimelineIconPolicy.iconFor("SHULKER_TAKE", true, true));
    }

    @Test void rowsAboutAPlayerCarryingItShowThatPlayersHead() {
        assertEquals(TimelineIcon.ACTOR_HEAD, TimelineIconPolicy.iconFor("PICKUP", true, true));
        assertEquals(TimelineIcon.ACTOR_HEAD, TimelineIconPolicy.iconFor("DROP", true, true));
        assertEquals(TimelineIcon.ACTOR_HEAD, TimelineIconPolicy.iconFor("DEATH", true, true));
        assertEquals(TimelineIcon.ACTOR_HEAD,
            TimelineIconPolicy.iconFor("INVENTORY_MOVE", true, true));
        assertEquals(TimelineIcon.ACTOR_HEAD, TimelineIconPolicy.iconFor("SPAWN", true, true));
    }

    @Test void destructionRowsShowWhyTheItemIsGone() {
        assertEquals(TimelineIcon.BURNED, TimelineIconPolicy.iconFor("BURNED", false, true));
        assertEquals(TimelineIcon.CLEARED, TimelineIconPolicy.iconFor("CLEARED", false, true));
        assertEquals(TimelineIcon.DESPAWNED, TimelineIconPolicy.iconFor("DESPAWNED", false, true));
    }

    @Test void containerIconsDoNotDependOnKnowingTheActor() {
        // The place is the subject of the row, so redaction cannot change it.
        assertEquals(TimelineIcon.CHEST, TimelineIconPolicy.iconFor("CONTAINER_PUT", false, false));
        assertEquals(TimelineIcon.ENDER_CHEST,
            TimelineIconPolicy.iconFor("ENDERCHEST_TAKE", true, false));
    }

    @Test void aPlayerRowWithoutAVisibleActorFallsBackToAPlainHead() {
        assertEquals(TimelineIcon.DEFAULT_HEAD, TimelineIconPolicy.iconFor("PICKUP", false, true));
        assertEquals(TimelineIcon.DEFAULT_HEAD, TimelineIconPolicy.iconFor("DROP", true, false));
    }

    @Test void anUnknownActionShowsAPlainHeadRatherThanGuessingAPlace() {
        assertEquals(TimelineIcon.DEFAULT_HEAD,
            TimelineIconPolicy.iconFor("SOMETHING_NEW", true, true));
        assertEquals(TimelineIcon.DEFAULT_HEAD, TimelineIconPolicy.iconFor(null, true, true));
    }

    @Test void caseDoesNotMatter() {
        assertEquals(TimelineIcon.CHEST, TimelineIconPolicy.iconFor("container_put", true, true));
        assertEquals(TimelineIcon.ENDER_CHEST,
            TimelineIconPolicy.iconFor("enderchest_take", true, true));
    }

    @Test void everyIconNamesTheMaterialItRenders() {
        for (TimelineIcon icon : TimelineIcon.values()) {
            assertNotNull(icon.material(), icon.name());
        }
    }
}
