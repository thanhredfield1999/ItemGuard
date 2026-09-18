package com.itemguard.commands;

import com.itemguard.data.ItemData;
import com.itemguard.dupe.FindingAcknowledgement;
import com.itemguard.dupe.FindingRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin reports are read by a person under pressure, so the wording carries meaning: "not
 * tracked" must not read like an error, and an unsupported acknowledgement must not read like a
 * successful no-op. These tests pin the sentences that would otherwise drift.
 */
class FindItemReportTaskTest {

    private static final UUID ITEM_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** A tracked row as the database hands one back, with the fields the reports print. */
    private static ItemData trackedItem(String code) {
        ItemData item = new ItemData(code, ITEM_UUID);
        item.setItemName("Diamond Sword");
        item.setOwnerName("ThanhRedfield");
        item.setLastAction("PICKUP");
        item.setCreatedAt(1_699_000_000_000L);
        item.setLastSeenAt(1_700_000_000_000L);
        item.setDetectionCount(1);
        return item;
    }

    private static final class FakeData implements FindItemReportTask.Data {
        private final List<String> requestedCodes = new ArrayList<>();
        private final List<String> requestedPlayers = new ArrayList<>();
        private ItemData item;
        private int historyCount = 7;
        private boolean snapshotPresent = true;
        private List<FindingRecord> findings = List.of();
        private FindingAcknowledgement acknowledgement = FindingAcknowledgement.of(0);

        @Override
        public Optional<ItemData> item(String code) {
            requestedCodes.add(code);
            if (item == null || !item.getCode().equals(code)) {
                return Optional.empty();
            }
            return Optional.of(item);
        }

        @Override
        public int historyCount(String code) {
            return historyCount;
        }

        @Override
        public boolean snapshotPresent(String code) {
            return snapshotPresent;
        }

        @Override
        public List<FindingRecord> findings(String code, int limit) {
            return findings;
        }

        @Override
        public FindingAcknowledgement acknowledge(String code, String actor, long acknowledgedAt) {
            return acknowledgement;
        }

        @Override
        public Optional<UUID> onlinePlayer(String name) {
            requestedPlayers.add(name);
            return name.equalsIgnoreCase("ThanhRedfield") ? Optional.of(ITEM_UUID) : Optional.empty();
        }

        @Override
        public List<ItemData> itemsByPlayer(UUID playerUuid) {
            return item == null ? List.of() : List.of(item);
        }
    }

    private FindItemReportTask.ScanSnapshot scan(boolean running, int samples) {
        return new FindItemReportTask.ScanSnapshot(
            40, 39, 3, 2, 5, 1, samples, 12.5, 9.75, 9.0, 18.5, false, 600, false, true, running
        );
    }

    @Test
    void checkTpsReportsThePluginsOwnNumbersAndSaysSo() {
        String report = String.join("\n", new FindItemReportTask(new FakeData()).checkTps(scan(true, 40)));

        assertTrue(report.contains("Scans: §f40"), report);
        assertTrue(report.contains("p50 9.0"), report);
        assertTrue(report.contains("p95 18.5"), report);
        assertTrue(report.contains("not server TPS"),
            "a command named checktps must not let a reader mistake these for server tick times");
    }

    @Test
    void checkTpsSaysSoWhenTheScanTaskIsNotRunning() {
        String report = String.join("\n", new FindItemReportTask(new FakeData()).checkTps(scan(false, 0)));

        assertTrue(report.contains("disabled"), report);
        assertTrue(report.contains("inventory-scan-interval = 0"),
            "an operator has to know which key turned it off, not just that nothing runs");
        assertFalse(report.contains("p95"), "no samples means no percentiles to print");
    }

    @Test
    void infoItemSeparatesUnknownFromErroredAndWarnsAboutAMissingSnapshot() {
        FakeData unknown = new FakeData();
        String missing = String.join("\n", new FindItemReportTask(unknown).infoItem("ZZ9999"));
        assertTrue(missing.contains("Not tracked: §fZZ9999"), missing);
        assertTrue(missing.contains("no record of that identity"), missing);

        FakeData known = new FakeData();
        known.item = trackedItem("AB12CD");
        known.snapshotPresent = false;
        String found = String.join("\n", new FindItemReportTask(known).infoItem("AB12CD"));
        assertTrue(found.contains("Diamond Sword"), found);
        assertTrue(found.contains("History entries: §f7"), found);
        assertTrue(found.contains("missing"), found);
        assertTrue(found.contains("cannot hand back the real item"),
            "the consequence of a missing snapshot has to be stated where an admin decides about a reclaim");
    }

    @Test
    void infoPlayerRefusesToGuessForAnOfflinePlayer() {
        String report = String.join("\n", new FindItemReportTask(new FakeData()).infoPlayer("SomeoneElse"));

        assertTrue(report.contains("not online"), report);
        assertTrue(report.contains("offline lookup would be a guess"),
            "a name-keyed guess would show another account's records");
    }

    @Test
    void infoPlayerListsWhatTheRecordsHold() {
        FakeData data = new FakeData();
        data.item = trackedItem("AB12CD");

        String report = String.join("\n", new FindItemReportTask(data).infoPlayer("ThanhRedfield"));

        assertTrue(report.contains("Tracked items: §f1"), report);
        assertTrue(report.contains("AB12CD"), report);
    }

    @Test
    void infoDupeReportsNoFindingWithoutClaimingThereIsNone() {
        String report = String.join("\n", new FindItemReportTask(new FakeData()).infoDupe("AB12CD"));

        assertTrue(report.contains("No duplicate finding"), report);
        assertTrue(report.contains("not proof of absence"), report);
    }

    @Test
    void infoDupeCountsUnreadFindingsAndKeepsTheCaveat() {
        FakeData data = new FakeData();
        data.findings = List.of(
            new FindingRecord(1L, "AB12CD", ITEM_UUID.toString(), 10L, "CONFIRMED", 2, "NOTIFY",
                1_700_000_000_000L, "seen PICKUP then CONTAINER_PUT", null, null),
            new FindingRecord(2L, "AB12CD", ITEM_UUID.toString(), 11L, "CONFIRMED", 3, "NOTIFY",
                1_700_000_060_000L, null, 1_700_000_100_000L, "ThanhRedfield")
        );

        String report = String.join("\n", new FindItemReportTask(data).infoDupe("AB12CD"));

        assertTrue(report.contains("Findings recorded: §f2"), report);
        assertTrue(report.contains("unread: §f1"), report);
        assertTrue(report.contains("read by ThanhRedfield"), report);
        assertTrue(report.contains("not by itself proof that a copy was made"),
            "the evidence line must stay balanced: two sightings are not a dupe verdict");
    }

    @Test
    void readFindingShowsTheDetailLineWhenThereIsOne() {
        FakeData data = new FakeData();
        data.findings = List.of(new FindingRecord(1L, "AB12CD", ITEM_UUID.toString(), 10L, "CONFIRMED", 2,
            "NOTIFY", 1_700_000_000_000L, "epoch 10: 2 locations", null, null));

        String report = String.join("\n", new FindItemReportTask(data).readFinding("AB12CD"));

        assertTrue(report.contains("#1"), report);
        assertTrue(report.contains("epoch 10: 2 locations"), report);
    }

    @Test
    void anUnsupportedAcknowledgementIsNeverReportedAsZeroMarked() {
        FakeData data = new FakeData();
        data.acknowledgement = FindingAcknowledgement.noneOnThisBackend();

        String report = String.join("\n", new FindItemReportTask(data).acknowledgeDupe(
            "AB12CD", "ThanhRedfield", 1_700_000_000_000L
        ));

        assertTrue(report.contains("needs the MySQL backend"), report);
        assertTrue(report.contains("SQLITE"), report);
        assertFalse(report.contains("0"),
            "reporting zero would be indistinguishable from 'nothing needed reading'");
    }

    @Test
    void acknowledgingNothingUnreadSaysExactlyThat() {
        FakeData data = new FakeData();
        data.acknowledgement = FindingAcknowledgement.of(0);

        String report = String.join("\n", new FindItemReportTask(data).acknowledgeDupe(
            "AB12CD", "ThanhRedfield", 1_700_000_000_000L
        ));

        assertTrue(report.contains("No unread finding"), report);
    }

    @Test
    void acknowledgingReportsWhoDidIt() {
        FakeData data = new FakeData();
        data.acknowledgement = FindingAcknowledgement.of(2);

        String report = String.join("\n", new FindItemReportTask(data).acknowledgeDupe(
            "AB12CD", "ThanhRedfield", 1_700_000_000_000L
        ));

        assertTrue(report.contains("Marked §f2"), report);
        assertTrue(report.contains("as read by §fThanhRedfield"), report);
    }
}
