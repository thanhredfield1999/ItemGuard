package com.itemguard.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteConnectionOwnerFailClosedWiringContractTest {

    @Test
    void everyNormalCloseFailureRetainsProcessLockUntilExit() throws Exception {
        String source = Files.readString(
            Path.of("src/main/java/com/itemguard/persistence/SqliteConnectionOwner.java"),
            StandardCharsets.UTF_8
        );

        int start = source.indexOf("boolean close(Duration timeout)");
        int end = source.indexOf("@Override", start);
        assertTrue(start >= 0 && end > start);
        String closeMethod = source.substring(start, end);

        assertEquals(4, occurrences(closeMethod, "processLock.retainUntilProcessExit();"));
        assertEquals(4, occurrences(closeMethod, "return false;"));
        assertEquals(1, occurrences(closeMethod, "processLockReleaser.accept(processLock);"));

        int release = closeMethod.indexOf("processLockReleaser.accept(processLock);");
        int releaseCatch = closeMethod.indexOf(
            "catch (RuntimeException releaseFailure)",
            release
        );
        int report = closeMethod.indexOf(
            "reportFailure(releaseFailure);",
            releaseCatch
        );
        int retain = closeMethod.indexOf(
            "processLock.retainUntilProcessExit();",
            releaseCatch
        );
        int failedOutcome = closeMethod.indexOf("closeSucceeded = false;", retain);
        int falseReturn = closeMethod.indexOf("return false;", failedOutcome);

        assertTrue(release >= 0);
        assertTrue(releaseCatch > release);
        assertTrue(report > releaseCatch);
        assertTrue(retain > report);
        assertTrue(failedOutcome > retain);
        assertTrue(falseReturn > failedOutcome);
    }

    private int occurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
