package com.itemguard.commands;

import com.itemguard.data.ItemData;
import com.itemguard.dupe.FindingAcknowledgement;
import com.itemguard.dupe.FindingRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The reporting half of {@code /finditem}: what an admin learns from the plugin's own records.
 *
 * <p>Split from {@link FindItemCommandTask} (which drives the search requests) and deliberately pure:
 * it holds no Bukkit types and takes a {@link Data} port plus a {@link ScanSnapshot}, so the wording
 * and the arithmetic of every line can be tested without a server, and the command class only has to
 * wire the port to the plugin and run it off the server thread.
 *
 * <p>Two rules shape the text. A record that is absent is reported as "not tracked" rather than as an
 * error — the honest state for an identity this plugin never saw. And a read that does not exist on
 * this backend (finding acknowledgement on SQLite) is reported as unsupported, never as "0 marked
 * read", because those mean opposite things to whoever is standing at the console.
 */
public final class FindItemReportTask {

    /** Everything the reports read. Implemented over the plugin's database in the command class. */
    public interface Data {
        Optional<ItemData> item(String code);

        int historyCount(String code);

        boolean snapshotPresent(String code);

        List<FindingRecord> findings(String code, int limit);

        FindingAcknowledgement acknowledge(String code, String actor, long acknowledgedAt);

        Optional<UUID> onlinePlayer(String name);

        List<ItemData> itemsByPlayer(UUID playerUuid);
    }

    /** The scan's numbers, copied out of {@code ScanMetrics} so this class stays free of Bukkit. */
    public record ScanSnapshot(
        long scans,
        long epochs,
        long findings,
        long alerts,
        long sweepPasses,
        long skippedBusyScans,
        int samples,
        double lastMillis,
        double averageMillis,
        double p50Millis,
        double p95Millis,
        boolean sweepInFlight,
        int intervalTicks,
        boolean antiDupeEnabled,
        boolean notifyStaff,
        boolean running
    ) {}

    private static final int FINDING_LIMIT = 20;

    private final Data data;

    public FindItemReportTask(Data data) {
        this.data = Objects.requireNonNull(data, "data");
    }

    public List<String> checkTps(ScanSnapshot scan) {
        Objects.requireNonNull(scan, "scan");
        List<String> messages = new ArrayList<>();
        messages.add(plugin("=== FindItem metrics ==="));
        if (!scan.running()) {
            messages.add("§7Scan task: §cdisabled §7(performance.inventory-scan-interval = 0)");
            return List.copyOf(messages);
        }
        messages.add("§7Interval: §f" + scan.intervalTicks() + " §7ticks"
            + " §8| §7sweep: " + (scan.sweepInFlight() ? "§ein flight" : "§aidle"));
        messages.add("§7Scans: §f" + scan.scans()
            + " §8| §7skipped (sweep busy): §f" + scan.skippedBusyScans()
            + " §8| §7sweep passes: §f" + scan.sweepPasses());
        messages.add("§7Epochs finalized: §f" + scan.epochs()
            + " §8| §7findings: §f" + scan.findings()
            + " §8| §7alerts sent: §f" + scan.alerts());
        if (scan.samples() == 0) {
            messages.add("§7Scan duration: §8no samples yet");
        } else {
            messages.add("§7Scan duration ms: §flast " + round(scan.lastMillis())
                + " §8| §favg " + round(scan.averageMillis())
                + " §8| §fp50 " + round(scan.p50Millis())
                + " §8| §fp95 " + round(scan.p95Millis())
                + " §8(§7" + scan.samples() + "§8)");
        }
        messages.add("§7Detection: anti-dupe " + (scan.antiDupeEnabled() ? "§aon" : "§coff")
            + " §8| §7staff alerts " + (scan.notifyStaff() ? "§aon" : "§coff"));
        messages.add("§7These are this plugin's own numbers, not server TPS.");
        return List.copyOf(messages);
    }

    public List<String> infoItem(String code) {
        List<String> messages = new ArrayList<>();
        messages.add(plugin("=== Item " + code + " ==="));
        Optional<ItemData> item = data.item(code);
        if (item.isEmpty()) {
            messages.add("§cNot tracked: §f" + code
                + " §7(this plugin has no record of that identity)");
            return List.copyOf(messages);
        }
        ItemData row = item.orElseThrow();
        messages.add("§7Material: §f" + row.getDisplayName()
            + " §8| §7owner: §f" + (row.getOwnerName() == null ? "unknown" : row.getOwnerName()));
        messages.add("§7Created: §f" + formatTime(row.getCreatedAt())
            + " §8| §7last seen: §f" + formatTime(row.getLastSeenAt()));
        messages.add("§7Last action: §f" + (row.getLastAction() == null ? "unknown" : row.getLastAction())
            + " §8| §7detections: §f" + row.getDetectionCount());
        messages.add("§7History entries: §f" + data.historyCount(code)
            + " §8| §7snapshot: " + (data.snapshotPresent(code) ? "§astored" : "§cmissing"));
        if (row.getLastLocation() != null) {
            messages.add("§7Last location: §f" + row.getLastLocation());
        }
        messages.add("§7A missing snapshot means a reclaim for this identity cannot hand back the "
            + "real item.");
        return List.copyOf(messages);
    }

    public List<String> infoPlayer(String playerName) {
        List<String> messages = new ArrayList<>();
        messages.add(plugin("=== Player " + playerName + " ==="));
        Optional<UUID> uuid = data.onlinePlayer(playerName);
        if (uuid.isEmpty()) {
            messages.add("§cThat player is not online; §7the record view is keyed by UUID, so an "
                + "offline lookup would be a guess.");
            return List.copyOf(messages);
        }
        List<ItemData> items = data.itemsByPlayer(uuid.orElseThrow());
        if (items.isEmpty()) {
            messages.add("§7No tracked items are recorded for this player.");
            messages.add("§7Records are written when an identity is adopted or recorded, so an empty "
                + "answer does not prove the player never held a tracked item.");
            return List.copyOf(messages);
        }
        messages.add("§7Tracked items: §f" + items.size());
        int shown = 0;
        for (ItemData item : items) {
            if (shown++ >= 10) {
                messages.add("§8... and " + (items.size() - 10) + " more");
                break;
            }
            messages.add("§7- §f" + item.getCode()
                + " §8| §e" + item.getDisplayName()
                + " §8| §7last §f" + (item.getLastAction() == null ? "unknown" : item.getLastAction()));
        }
        return List.copyOf(messages);
    }

    public List<String> infoDupe(String code) {
        List<String> messages = new ArrayList<>();
        messages.add(plugin("=== Duplicate evidence " + code + " ==="));
        List<FindingRecord> findings = data.findings(code, FINDING_LIMIT);
        if (findings.isEmpty()) {
            messages.add("§7No duplicate finding is recorded for this identity.");
            messages.add("§7That is not proof of absence: findings are written only while detection "
                + "runs, and each epoch's row is what the audit kept.");
            return List.copyOf(messages);
        }
        messages.add("§7Findings recorded: §f" + findings.size()
            + " §8| §7unread: §f" + findings.stream().filter(f -> !f.acknowledged()).count());
        for (FindingRecord finding : findings) {
            messages.add("§7- §fepoch " + finding.scanEpoch()
                + " §8| §e" + finding.status()
                + " §8| §7locations §f" + finding.distinctLocations()
                + " §8| §7action §f" + finding.action()
                + " §8| §7" + (finding.acknowledged()
                    ? "read by " + finding.acknowledgedBy()
                    : "§cunread"));
        }
        messages.add("§7A finding says the identity was seen in more than one place; it is not by "
            + "itself proof that a copy was made.");
        return List.copyOf(messages);
    }

    public List<String> readFinding(String code) {
        List<String> messages = new ArrayList<>();
        messages.add(plugin("=== Findings for " + code + " ==="));
        List<FindingRecord> findings = data.findings(code, FINDING_LIMIT);
        if (findings.isEmpty()) {
            messages.add("§7Nothing recorded for that identity.");
            return List.copyOf(messages);
        }
        for (FindingRecord finding : findings) {
            messages.add("§7#" + finding.findingId()
                + " §8| §7epoch §f" + finding.scanEpoch()
                + " §8| §e" + finding.status()
                + " §8| §7" + formatTime(finding.createdAt())
                + " §8| §7" + (finding.acknowledged() ? "read" : "§cunread"));
            if (finding.detail() != null && !finding.detail().isBlank()) {
                messages.add("§8    " + finding.detail());
            }
        }
        return List.copyOf(messages);
    }

    public List<String> acknowledgeDupe(String code, String actor, long now) {
        FindingAcknowledgement result = data.acknowledge(code, actor, now);
        if (!result.supported()) {
            return List.of(
                plugin("§eAcknowledgement needs the MySQL backend."),
                "§7This server runs SQLITE, whose schema has no acknowledgement columns; the plugin "
                    + "will not report a write it did not make. Use §f/ig migrate §7to move to MySQL."
            );
        }
        if (result.acknowledged() == 0) {
            return List.of(plugin("§7No unread finding for §f" + code + "§7."));
        }
        return List.of(plugin("§aMarked §f" + result.acknowledged() + " §afinding(s) for §f"
            + code + " §aas read by §f" + actor + "§a."));
    }

    private static String plugin(String message) {
        return "§e§l[ItemGuard] §r" + message;
    }

    private static String round(double millis) {
        return String.valueOf(Math.round(millis * 10.0) / 10.0);
    }

    private static String formatTime(long timestamp) {
        if (timestamp <= 0L) {
            return "unknown";
        }
        return java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(timestamp));
    }
}
