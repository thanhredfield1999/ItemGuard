package com.itemguard.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilterInputPolicyTest {

    private final FilterInputPolicy policy = new FilterInputPolicy();

    @Test
    void cancelKeywordProducesCancelAction() {
        assertEquals(FilterInputAction.CANCEL, policy.resolve(" Huy "));
    }

    @Test
    void wildcardProducesShowAllAction() {
        assertEquals(FilterInputAction.SHOW_ALL, policy.resolve(" * "));
    }

    @Test
    void blankInputProducesShowAllAction() {
        assertEquals(FilterInputAction.SHOW_ALL, policy.resolve("   "));
    }

    @Test
    void textProducesNormalizedFilterAction() {
        assertEquals(new FilterInputAction.Apply("diamond_sword"),
            policy.resolve(" diamond_sword "));
    }
}
