package com.itemguard.gui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiSessionLifecycleWiringContractTest {

    @Test
    void guiAndFilterSessionsAreReleasedOnCloseQuitAndDisable() throws Exception {
        String gui = source("gui/GUIListener.java");
        String filter = source("gui/FilterChatListener.java");
        String browser = source("gui/PlayerBrowserGUI.java");
        String player = source("listeners/PlayerListener.java");
        String plugin = source("ItemGuard.java");

        assertTrue(gui.contains("InventoryCloseEvent"));
        assertTrue(gui.contains("releasePlayer("));
        assertTrue(gui.contains("openGUIs.remove(playerUuid)"));
        assertTrue(gui.contains("openBrowsers.remove(playerUuid)"));
        assertTrue(filter.contains("void releasePlayer(UUID playerUuid)"));
        assertTrue(filter.contains("pendingFilters.remove(playerUuid)"));
        assertTrue(browser.contains("plugin.getGuiListener().registerOpenBrowser(viewer, this)"));
        // PlayerListener must still release the GUI session on quit, but the call is now
        // null-guarded: LITE never constructs the GUI stack (it resolves Adventure, absent on
        // Spigot), so getGuiListener() is null there. Assert the release still happens rather
        // than pinning one exact call shape.
        assertTrue(player.contains("getGuiListener()"));
        assertTrue(player.contains("releasePlayer(event.getPlayer().getUniqueId())"));
        assertTrue(plugin.contains("guiListener.clearSessions()"));
        assertTrue(plugin.contains("filterChatListener.clearSessions()"));
    }

    private String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/itemguard/" + relative));
    }
}
