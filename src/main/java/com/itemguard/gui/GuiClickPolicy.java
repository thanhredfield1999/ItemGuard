package com.itemguard.gui;

public final class GuiClickPolicy {

    public GuiClickAction resolve(boolean itemGuardGui, boolean shiftClick) {
        if (!itemGuardGui) {
            return GuiClickAction.IGNORE;
        }
        return shiftClick ? GuiClickAction.CANCEL_ONLY : GuiClickAction.CANCEL_AND_HANDLE;
    }
}
