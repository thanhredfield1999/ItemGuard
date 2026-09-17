package com.itemguard.gui;

import org.bukkit.plugin.IllegalPluginAccessException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiMainThreadHandoffTest {

    @Test
    void disabledPluginDoesNotSubmitCallback() {
        AtomicBoolean submitted = new AtomicBoolean();

        assertFalse(UiMainThreadHandoff.submit(false, () -> submitted.set(true)));
        assertFalse(submitted.get());
    }

    @Test
    void disableRaceIsRejectedWithoutEscaping() {
        assertFalse(UiMainThreadHandoff.submit(true, () -> {
            throw new IllegalPluginAccessException("plugin disabled");
        }));
    }

    @Test
    void enabledPluginSubmitsCallback() {
        AtomicBoolean submitted = new AtomicBoolean();

        assertTrue(UiMainThreadHandoff.submit(true, () -> submitted.set(true)));
        assertTrue(submitted.get());
    }
}
