package com.itemguard.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiClickPolicyTest {

    private final GuiClickPolicy policy = new GuiClickPolicy();

    @Test
    void normalClickInItemGuardGuiIsCancelledAndHandled() {
        assertEquals(
            GuiClickAction.CANCEL_AND_HANDLE,
            policy.resolve(true, false)
        );
    }

    @Test
    void shiftClickInItemGuardGuiIsCancelledWithoutAction() {
        assertEquals(
            GuiClickAction.CANCEL_ONLY,
            policy.resolve(true, true)
        );
    }

    @Test
    void foreignInventoryIsIgnored() {
        assertEquals(
            GuiClickAction.IGNORE,
            policy.resolve(false, true)
        );
        assertEquals(
            GuiClickAction.IGNORE,
            policy.resolve(false, false)
        );
    }
}
