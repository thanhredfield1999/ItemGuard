package com.itemguard.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code /finditem}'s reporting subcommands read the database with the blocking repository calls, so
 * where they are called from is the whole contract: inside the async dispatch, never before it.
 *
 * <p>The command already dispatched off-thread for the search-request actions, which is why this is
 * a source contract rather than a redesign — the risk is a later edit adding a "quick" read (an id
 * check, a permission lookup) to the main-thread part of {@code onCommand}, where it would block the
 * server on a database round trip. The player snapshot is asserted the other way round: it must be
 * taken <em>before</em> the dispatch, because reading the online-player list off-thread would be the
 * mirror-image mistake.
 */
class FindItemReportAsyncContractTest {

    private static final Path COMMAND =
        Path.of("src/main/java/com/itemguard/commands/FindItemCommand.java");

    @Test
    void everyDatabaseReadHappensAfterTheAsyncDispatch() throws Exception {
        String source = Files.readString(COMMAND);
        int dispatch = source.indexOf("runTaskAsynchronously");

        assertTrue(dispatch > 0, "the command must dispatch its work off the server thread");
        assertFalse(
            source.substring(0, dispatch).contains("plugin.getDB()"),
            "no database read may run on the server thread before the async dispatch"
        );
        assertTrue(
            source.indexOf("plugin.getDB()") > dispatch,
            "the database reads must live in the work the async dispatch runs"
        );
    }

    @Test
    void thePlayerSnapshotIsTakenOnTheServerThreadAndTheResultComesBackToIt() throws Exception {
        String source = Files.readString(COMMAND);
        int dispatch = source.indexOf("runTaskAsynchronously");
        int snapshot = source.indexOf("Bukkit.getOnlinePlayers()");

        assertTrue(snapshot > 0, "the report needs the online players to resolve a name to a UUID");
        assertTrue(
            snapshot < dispatch,
            "the online-player snapshot must be taken on the server thread, before the dispatch"
        );
        assertTrue(
            source.contains("Bukkit.getScheduler().runTask("),
            "the rendered messages must be handed back to the server thread"
        );
    }

    @Test
    void theAcknowledgementIsWrittenThroughTheDatabaseManager() throws Exception {
        String source = Files.readString(COMMAND);

        assertTrue(
            source.contains("acknowledgeFindings("),
            "readdupe must go through the database manager's acknowledgement path"
        );
        assertTrue(
            source.contains("System.currentTimeMillis()"),
            "the acknowledgement timestamp must be recorded rather than left to the default"
        );
    }
}
