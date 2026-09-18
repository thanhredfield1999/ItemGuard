package com.itemguard.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The notice that keeps {@code anti-dupe.action} from being a setting that does nothing quietly.
 */
class DestructiveAntiDupeNoticeTest {

    private final DestructiveAntiDupeNotice notice = new DestructiveAntiDupeNotice();

    @Test
    void aDestructiveChoiceIsAnnouncedWithTheEffectiveActionAndTheWayOut() {
        String warning = notice.warningFor("REMOVE_ALL").orElseThrow();

        assertTrue(warning.contains("REMOVE_ALL"), warning);
        assertTrue(warning.contains("NOTIFY"), warning);
        assertTrue(warning.contains("anti-dupe.action: NOTIFY"),
            "the owner has to be told how to stop the notice: " + warning);
    }

    @Test
    void everyDestructiveActionTheConfigOffersIsCovered() {
        for (String destructive : new String[] {"REMOVE_NEWER", "REMOVE_OLDER", "REMOVE_ALL"}) {
            assertTrue(notice.warningFor(destructive).isPresent(),
                destructive + " is offered by config.yml, so choosing it must be visible");
        }
    }

    @Test
    void notifyIsSilentBecauseNothingIsBeingIgnored() {
        assertTrue(notice.warningFor("NOTIFY").isEmpty());
        assertTrue(notice.warningFor("notify").isEmpty(),
            "case must not decide whether an owner is told");
    }

    @Test
    void junkAndBlankValuesAreReportedRatherThanTreatedAsNotify() {
        // A blank or misspelled action resolves to NOTIFY, which is not what the owner wrote; saying
        // so once at startup is cheaper than a support thread about a typo.
        assertTrue(notice.warningFor("").isPresent());
        assertTrue(notice.warningFor("   ").isPresent());
        assertTrue(notice.warningFor("REMOVE_EVERYTHING").isPresent());
        assertTrue(notice.warningFor(null).isPresent());
        assertTrue(notice.warningFor("(blank)").orElseThrow().contains("(blank)"),
            "an empty value must be named as blank rather than printed as nothing at all");
    }
}
