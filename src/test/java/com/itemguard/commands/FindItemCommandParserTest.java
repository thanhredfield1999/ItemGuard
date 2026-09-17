package com.itemguard.commands;

import com.itemguard.search.ItemSearchMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FindItemCommandParserTest {

    private final FindItemCommandParser parser = new FindItemCommandParser();

    @Test
    void parsesStartFindAndTakeCommands() {
        assertEquals(
            new FindItemCommandAction.Start("AB12CD", ItemSearchMode.FIND, Duration.ofMinutes(5)),
            parser.parse(new String[] {"startfinding", "ab12cd", "5m"})
        );
        assertEquals(
            new FindItemCommandAction.Start("EF34GH", ItemSearchMode.TAKE, Duration.ofHours(2)),
            parser.parse(new String[] {"starttaking", "#ef34gh", "2h"})
        );
    }

    @Test
    void parsesStopListRemoveAndConfirmedClear() {
        assertEquals(
            new FindItemCommandAction.Stop("AB12CD"),
            parser.parse(new String[] {"stopfinding", "ab12cd"})
        );
        assertEquals(
            new FindItemCommandAction.ListActive(3),
            parser.parse(new String[] {"listfinding", "3"})
        );
        assertEquals(
            new FindItemCommandAction.Remove("AB12CD"),
            parser.parse(new String[] {"removefinding", "ab12cd"})
        );
        assertEquals(
            FindItemCommandAction.ClearConfirmed.INSTANCE,
            parser.parse(new String[] {"clearfinding", "confirm"})
        );
    }

    @Test
    void rejectsMissingInvalidOrDestructiveUnreleasedSyntax() {
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(new String[] {"startfinding", "AB12CD"}));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(new String[] {"listfinding", "0"}));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(new String[] {"clearfinding"}));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(new String[] {"giveoldid", "AB12CD"}));
    }
}
