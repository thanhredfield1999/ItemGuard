package com.itemguard.search;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchDurationParserTest {

    private final SearchDurationParser parser = new SearchDurationParser();

    @Test
    void parsesSupportedUnits() {
        assertEquals(Duration.ofSeconds(30), parser.parse("30s"));
        assertEquals(Duration.ofMinutes(5), parser.parse("5m"));
        assertEquals(Duration.ofHours(2), parser.parse("2h"));
        assertEquals(Duration.ofDays(7), parser.parse("7d"));
    }

    @Test
    void rejectsMissingUnknownZeroOrExcessiveDuration() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("10"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("0m"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("1w"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("366d"));
    }
}
