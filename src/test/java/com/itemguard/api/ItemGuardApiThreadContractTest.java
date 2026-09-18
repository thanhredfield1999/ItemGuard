package com.itemguard.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The last open surface of IG-R022: {@code ItemGuardAPI} hands other plugins two lookups that read the
 * item database. A library cannot know its caller's thread, so the blocking form has to say what it
 * costs and an asynchronous form has to exist for callers on the server thread.
 *
 * <p>Measured before this contract (2026-09-19): both lookups were synchronous-only, and the
 * controller behind them ({@code DatabaseManager.getItemByUuid}) had no asynchronous counterpart at
 * all. A plugin with no other option would therefore stall the tick loop on a network database — the
 * exact failure IG-R022 exists to end.
 */
class ItemGuardApiThreadContractTest {

    private static final String API = "src/main/java/com/itemguard/api/ItemGuardAPI.java";
    private static final String SERVICE =
        "src/main/java/com/itemguard/services/ItemTrackingService.java";
    private static final String MANAGER = "src/main/java/com/itemguard/data/DatabaseManager.java";

    private static String read(String relative) throws Exception {
        return Files.readString(Path.of(relative));
    }

    /** The text from the method's first mention to the end of that method. */
    private static String methodBody(String source, String method) {
        int start = source.indexOf(method);
        if (start < 0) {
            return "";
        }
        int end = source.indexOf("\n    }", start);
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }

    @Test
    void theApiOffersAsynchronousLookupsAndDoesNotWaitInsideThem() throws Exception {
        String api = read(API);

        assertTrue(
            api.contains("CompletableFuture<Optional<ItemData>> getTrackedItemAsync(String code)"),
            "the API must offer an asynchronous item lookup");
        assertTrue(
            api.contains("CompletableFuture<Optional<ItemData>> getTrackedItemByUuidAsync(UUID uuid)"),
            "the API must offer an asynchronous lookup by item UUID");

        for (String method : new String[] {"getTrackedItemAsync", "getTrackedItemByUuidAsync"}) {
            String body = methodBody(api, method);
            assertFalse(body.isEmpty(), method + " must be implemented, not declared elsewhere");
            for (String blocking : new String[] {".join()", ".get()", ".getNow(", ".wait("}) {
                assertFalse(
                    body.contains(blocking),
                    method + " must return the future instead of waiting for it (" + blocking + ")");
            }
        }
    }

    @Test
    void theSynchronousLookupsAreMarkedAndSayWhatTheyCost() throws Exception {
        String api = read(API);

        for (String method : new String[] {"getTrackedItem", "getTrackedItemByUuid"}) {
            int declaration = api.indexOf("public Optional<ItemData> " + method + "(");
            assertTrue(declaration >= 0, method + " must remain available for compatibility");
            String before = api.substring(Math.max(0, declaration - 900), declaration);
            assertTrue(
                before.contains("@Deprecated(forRemoval = false)"),
                method + " must be marked as the blocking compatibility form");
            assertTrue(
                before.toLowerCase().contains("server thread"),
                method + " must name the server-thread rule in its javadoc so a plugin author sees it");
        }
    }

    @Test
    void theServiceAndManagerCarryTheAsynchronousPathTheApiNeeds() throws Exception {
        String service = read(SERVICE);
        String manager = read(MANAGER);

        assertTrue(
            service.contains("CompletableFuture<Optional<ItemData>> getTrackedItemAsync(String code)"),
            "the tracking service must forward the asynchronous lookup");
        assertTrue(
            service.contains("CompletableFuture<Optional<ItemData>> getTrackedItemByUuidAsync(UUID uuid)"),
            "the tracking service must forward the asynchronous lookup by UUID");
        assertTrue(
            manager.contains("CompletableFuture<Optional<ItemData>> getItemByUuidAsync(UUID itemUuid)"),
            "the database manager needs an asynchronous read by item UUID; the synchronous one has no "
                + "asynchronous counterpart, which is why a caller had nothing else to use");
        for (String method : new String[] {"getTrackedItemAsync", "getTrackedItemByUuidAsync"}) {
            String body = methodBody(service, method);
            assertFalse(body.isEmpty(), method + " must be implemented on the service");
            for (String blocking : new String[] {".join()", ".get()", ".getNow(", ".wait("}) {
                assertFalse(
                    body.contains(blocking),
                    method + " must not wait for the database on the calling thread (" + blocking + ")");
            }
        }
    }
}
