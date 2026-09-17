package com.itemguard.dupe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DuplicateDetectorTest {

    private final DuplicateDetector detector = new DuplicateDetector();
    private final UUID itemUuid = UUID.randomUUID();

    @Test
    void repeatedEventsAtSamePhysicalSlotRemainClean() {
        ObservationKey slot = new ObservationKey(HolderType.PLAYER, "player-a", 5);

        DuplicateAssessment assessment = detector.assess(itemUuid, 10, true, List.of(
            observation(itemUuid, 10, slot),
            observation(itemUuid, 10, slot),
            observation(itemUuid, 10, slot)
        ));

        assertEquals(DuplicateStatus.CLEAN, assessment.status());
        assertEquals(1, assessment.distinctLocations());
    }

    @Test
    void twoPhysicalSlotsInCompletedEpochAreConfirmed() {
        DuplicateAssessment assessment = detector.assess(itemUuid, 11, true, List.of(
            observation(itemUuid, 11, new ObservationKey(HolderType.PLAYER, "player-a", 5)),
            observation(itemUuid, 11, new ObservationKey(HolderType.PLAYER, "player-a", 8))
        ));

        assertEquals(DuplicateStatus.CONFIRMED, assessment.status());
        assertEquals(2, assessment.distinctLocations());
    }

    @Test
    void incompleteEpochCannotConfirmDuplicate() {
        DuplicateAssessment assessment = detector.assess(itemUuid, 12, false, List.of(
            observation(itemUuid, 12, new ObservationKey(HolderType.PLAYER, "player-a", 5)),
            observation(itemUuid, 12, new ObservationKey(HolderType.CONTAINER, "chest-a", 1))
        ));

        assertEquals(DuplicateStatus.SUSPECTED, assessment.status());
    }

    @Test
    void observationsFromOtherEpochsDoNotConfirmCurrentEpoch() {
        DuplicateAssessment assessment = detector.assess(itemUuid, 13, true, List.of(
            observation(itemUuid, 12, new ObservationKey(HolderType.PLAYER, "player-a", 5)),
            observation(itemUuid, 13, new ObservationKey(HolderType.PLAYER, "player-a", 8))
        ));

        assertEquals(DuplicateStatus.CLEAN, assessment.status());
    }

    @Test
    void observationsForOtherIdentityAreIgnored() {
        DuplicateAssessment assessment = detector.assess(itemUuid, 14, true, List.of(
            observation(itemUuid, 14, new ObservationKey(HolderType.PLAYER, "player-a", 5)),
            observation(UUID.randomUUID(), 14, new ObservationKey(HolderType.PLAYER, "player-a", 8))
        ));

        assertEquals(DuplicateStatus.CLEAN, assessment.status());
    }

    private ItemObservation observation(UUID uuid, long epoch, ObservationKey key) {
        return new ItemObservation(uuid, "AB12CD", epoch, key, 1_000L);
    }
}
