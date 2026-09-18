package com.itemguard.persistence;

import com.itemguard.data.ItemData;
import com.itemguard.dupe.FindingAcknowledgement;
import com.itemguard.dupe.FindingRecord;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQLite side of finding acknowledgement: it must refuse out loud.
 *
 * <p>The acknowledgement columns live on the MySQL schema only. Raising the SQLite ladder would make
 * a database written by this build unopenable by the published LITE jar, whose evidence is bound to
 * schema 8, so the honest state is "this backend cannot record it" — never a silent zero, which an
 * operator would read as "nothing needed reading".
 */
class SqliteFindingAcknowledgementTest {

    @TempDir
    Path tempDir;

    private static final UUID ITEM_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    private static void seedTrackedItem(ItemSqliteRepository repository, String code) {
        ItemData item = new ItemData(code, ITEM_UUID);
        item.setMaterial(Material.DIAMOND_SWORD);
        item.setItemName("Diamond Sword");
        item.setOwnerName("Thanh");
        repository.saveItem(item);
    }

    private static void insertFinding(SqliteConnectionOwner owner, String code, long epoch, String detail) {
        owner.execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO duplicate_findings
                        (code, item_uuid, scan_epoch, status, distinct_locations, action, created_at, detail)
                    VALUES (?, ?, ?, 'CONFIRMED', 2, 'NOTIFY', ?, ?)
                    """)) {
                statement.setString(1, code);
                statement.setString(2, ITEM_UUID.toString());
                statement.setLong(3, epoch);
                statement.setLong(4, 1_700_000_000_000L + epoch);
                statement.setString(5, detail);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Test
    void acknowledgingOnSqliteReportsThatItIsNotSupported() {
        List<Throwable> failures = new ArrayList<>();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("findings.db"), failures::add
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            seedTrackedItem(repository, "AB12CD");
            insertFinding(owner, "AB12CD", 5L, "epoch 5: 2 locations");

            FindingAcknowledgement acknowledgement =
                repository.acknowledgeFindings("AB12CD", "Thanh", 1_700_000_500_000L);

            assertFalse(acknowledgement.supported(),
                "SQLite has no acknowledgement columns, so the write must not claim to have happened");
            assertEquals(0, acknowledgement.acknowledged());
            assertTrue(failures.isEmpty(), "the refusal must not be a database failure: " + failures);
        }
    }

    @Test
    void findingsStillReadOnSqliteWithTheirReadStateUnknown() {
        List<Throwable> failures = new ArrayList<>();
        try (SqliteConnectionOwner owner = new SqliteConnectionOwner(
            tempDir.resolve("findings-read.db"), failures::add
        )) {
            ItemSqliteRepository repository = new ItemSqliteRepository(owner);
            seedTrackedItem(repository, "AB12CD");
            insertFinding(owner, "AB12CD", 5L, "epoch 5: 2 locations");
            insertFinding(owner, "AB12CD", 6L, null);

            List<FindingRecord> findings = repository.findingsFor("AB12CD", 20);

            assertEquals(2, findings.size(), "reading findings must work on both backends");
            for (FindingRecord finding : findings) {
                assertFalse(finding.acknowledged(),
                    "no ack state exists on SQLite, so nothing may read as acknowledged — "
                        + "including a row whose non-null detail could be mistaken for one");
                assertEquals("CONFIRMED", finding.status());
                assertEquals(2, finding.distinctLocations());
            }
            assertEquals(6L, findings.get(0).scanEpoch(), "newest finding first");
            assertTrue(failures.isEmpty());
        }
    }
}
