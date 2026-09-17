package com.itemguard.multiserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServerFindingPolicyTest {

    private static final UUID IDENTITY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long NOW = 1_700_000_000_000L;
    private static final long WINDOW = 30 * 60_000L;

    private final CrossServerFindingPolicy policy = new CrossServerFindingPolicy();

    private static CrossServerSighting sighting(String server, long observedAt) {
        return new CrossServerSighting(IDENTITY, server, observedAt, false);
    }

    @Test
    @DisplayName("one identity on two servers inside the window is reported, with both names")
    void twoServersAreReported() {
        CrossServerAssessment assessment = policy.assess(IDENTITY, NOW, WINDOW, List.of(
            sighting("survival-2", NOW - 60_000),
            sighting("survival-1", NOW - 120_000)
        ));
        assertEquals(CrossServerStatus.SEEN_ON_MULTIPLE_SERVERS, assessment.status());
        assertEquals(List.of("survival-1", "survival-2"), assessment.servers(),
            "servers are named in a stable order so the message does not shuffle");
        assertEquals(2, assessment.distinctServers());
        assertTrue(assessment.statement().contains("survival-1")
            && assessment.statement().contains("survival-2"), assessment.statement());
    }

    @Test
    @DisplayName("a server column does not fire: neither name is authoritative")
    void oneServerIsNotReported() {
        assertEquals(CrossServerStatus.NONE, policy.assess(IDENTITY, NOW, WINDOW, List.of(
            sighting("survival-1", NOW - 60_000),
            sighting("survival-1", NOW - 120_000)
        )).status());
        assertEquals(CrossServerStatus.NONE,
            policy.assess(IDENTITY, NOW, WINDOW, List.of()).status());
    }

    @Test
    @DisplayName("one server is named but not reported; no sighting names nothing at all")
    void oneServerIsNamedButNotReported() {
        CrossServerAssessment single = policy.assess(IDENTITY, NOW, WINDOW,
            List.of(sighting("survival-1", NOW - 1_000)));
        assertEquals(CrossServerStatus.NONE, single.status());
        assertEquals(List.of("survival-1"), single.servers());
        assertEquals(1, single.distinctServers(),
            "the count must always agree with the names, or the message contradicts itself");
        assertFalse(single.isReportable());
        assertTrue(single.statement().contains("survival-1"), single.statement());

        CrossServerAssessment none = policy.assess(IDENTITY, NOW, WINDOW, List.of());
        assertEquals(List.of(), none.servers());
        assertEquals(0, none.distinctServers());
        assertEquals("not seen inside the window", none.statement());
    }

    @Test
    @DisplayName("a sighting outside the window, or expired, is not evidence")
    void staleAndExpiredSightingsAreIgnored() {
        assertEquals(CrossServerStatus.NONE, policy.assess(IDENTITY, NOW, WINDOW, List.of(
            sighting("survival-1", NOW - 60_000),
            sighting("survival-2", NOW - WINDOW - 1)
        )).status(), "one millisecond past the window is outside it");
        assertEquals(CrossServerStatus.SEEN_ON_MULTIPLE_SERVERS,
            policy.assess(IDENTITY, NOW, WINDOW, List.of(
                sighting("survival-1", NOW - WINDOW),
                sighting("survival-2", NOW)
            )).status(), "the window is closed at both ends");
        assertEquals(CrossServerStatus.NONE, policy.assess(IDENTITY, NOW, WINDOW, List.of(
            sighting("survival-1", NOW - 60_000),
            new CrossServerSighting(IDENTITY, "survival-2", NOW - 60_000, true)
        )).status(), "an expired row is not a current sighting");
        assertEquals(CrossServerStatus.NONE, policy.assess(IDENTITY, NOW, WINDOW, List.of(
            sighting("survival-1", NOW - 60_000),
            sighting("survival-2", NOW + 60_000)
        )).status(), "a sighting in the future is not this window's evidence");
    }

    @Test
    @DisplayName("another identity's sightings are not this identity's")
    void otherIdentitiesAreIgnored() {
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        List<CrossServerSighting> sightings = new ArrayList<>();
        sightings.add(sighting("survival-1", NOW - 60_000));
        sightings.add(new CrossServerSighting(other, "survival-2", NOW - 60_000, false));
        sightings.add(null);
        assertEquals(CrossServerStatus.NONE,
            policy.assess(IDENTITY, NOW, WINDOW, sightings).status());
    }

    @Test
    @DisplayName("three servers are all named, and counted")
    void everyServerIsNamed() {
        CrossServerAssessment assessment = policy.assess(IDENTITY, NOW, WINDOW, List.of(
            sighting("survival-3", NOW - 1_000),
            sighting("survival-1", NOW - 2_000),
            sighting("survival-1", NOW - 3_000),
            sighting("survival-2", NOW - 4_000)
        ));
        assertEquals(3, assessment.distinctServers());
        assertEquals(List.of("survival-1", "survival-2", "survival-3"), assessment.servers());
    }

    @Test
    @DisplayName("a window that cannot report is refused, not accepted as configured")
    void aWindowThatCannotReportIsRefused() {
        for (long window : new long[] {0, -1}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> policy.assess(IDENTITY, NOW, window, List.of()));
            assertTrue(failure.getMessage().contains("reports nothing"),
                "unexpected message: " + failure.getMessage());
        }
    }

    @Test
    @DisplayName("no status or message may claim duplication: the data cannot support it")
    void theWordingNeverClaimsDuplication() {
        for (CrossServerStatus status : CrossServerStatus.values()) {
            assertFalse(status.name().contains("DUPLICATE") || status.name().contains("COPY")
                    || status.name().contains("DUPE"),
                "a status name is a claim: " + status);
        }
        String[] forbidden = {"duplicate", "dupe", "copy", "copied", "cloned"};
        for (CrossServerStatus status : CrossServerStatus.values()) {
            String statement = new CrossServerAssessment(status, List.of("survival-1"), 1).statement();
            for (String word : forbidden) {
                assertFalse(statement.toLowerCase().contains(word),
                    "the statement must say where it was seen, not what it means: " + statement);
            }
        }
    }

    @Test
    @DisplayName("a sighting without a server, or with a negative time, is refused at construction")
    void malformedSightingsAreRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new CrossServerSighting(IDENTITY, "  ", NOW, false));
        assertThrows(IllegalArgumentException.class,
            () -> new CrossServerSighting(IDENTITY, null, NOW, false));
        assertThrows(IllegalArgumentException.class,
            () -> new CrossServerSighting(IDENTITY, "survival-1", -1, false));
        assertThrows(NullPointerException.class,
            () -> new CrossServerSighting(null, "survival-1", NOW, false));
    }

    @Test
    @DisplayName("the assessment refuses a server list that disagrees with its count")
    void inconsistentAssessmentIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new CrossServerAssessment(CrossServerStatus.SEEN_ON_MULTIPLE_SERVERS,
                Arrays.asList("survival-1"), 2));
    }
}
