package com.itemguard.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MatDoCommandParserTest {

    private final MatDoCommandParser parser = new MatDoCommandParser();

    @Test
    void parsesCheckAndSos() {
        assertEquals(MatDoCommandAction.Check.INSTANCE,
            parser.parse(new String[] {"check"}));
        assertEquals(new MatDoCommandAction.Sos("AB12CD"),
            parser.parse(new String[] {"sos", "ab12cd"}));
        assertEquals(new MatDoCommandAction.Sos("AB12CD"),
            parser.parse(new String[] {"sos", "#ab12cd"}));
    }

    @Test
    void rejectsMissingExtraAndUnknownArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(new String[0]));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(new String[] {"check", "extra"}));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(new String[] {"sos"}));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(new String[] {"give", "AB12CD"}));
    }
}
