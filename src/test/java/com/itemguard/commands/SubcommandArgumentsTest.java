package com.itemguard.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class SubcommandArgumentsTest {

    @Test
    void removesOnlyTheSubcommandToken() {
        assertArrayEquals(
            new String[] {"Thanh", "50"},
            SubcommandArguments.tail(new String[] {"search", "Thanh", "50"})
        );
    }

    @Test
    void emptyOrSingleArgumentProducesEmptyTail() {
        assertArrayEquals(new String[0], SubcommandArguments.tail(new String[0]));
        assertArrayEquals(
            new String[0],
            SubcommandArguments.tail(new String[] {"history"})
        );
    }
}
