package premiumprobe;

import com.itemguard.ItemGuard;
import com.itemguard.data.DatabaseManager;
import com.itemguard.data.ItemData;
import com.itemguard.dupe.DuplicateAssessment;
import com.itemguard.dupe.DuplicateDetector;
import com.itemguard.dupe.HolderType;
import com.itemguard.dupe.ItemObservation;
import com.itemguard.dupe.ObservationKey;
import com.itemguard.multiserver.CrossServerAssessment;
import com.itemguard.multiserver.CrossServerFindingPolicy;
import com.itemguard.multiserver.CrossServerSighting;
import com.itemguard.multiserver.CrossServerStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class TwoServerProbe extends JavaPlugin {
    private static final UUID ITEM_UUID = UUID.fromString("00000000-0000-0000-0000-000000000062");
    private static final String CODE = "PAPER2S";
    private static final long NOW = 1_700_000_000_000L;
    private static final long SERVER_ONE_EPOCH = 700L;
    private static final long SERVER_TWO_EPOCH = 701L;

    private Path receipt;
    private String serverId;

    @Override
    public void onEnable() {
        receipt = getDataFolder().toPath().resolve("probe-receipt.json");
        serverId = getConfig().getString("probe.server-id", "unknown");
        Bukkit.getScheduler().runTaskAsynchronously(this, this::runProbe);
    }

    private void runProbe() {
        try {
            if ("server-1".equals(serverId)) {
                runServerOne();
            } else if ("server-2".equals(serverId)) {
                runServerTwo();
            } else {
                throw new IllegalStateException("unknown server-id " + serverId);
            }
        } catch (Throwable failure) {
            fail(failure.toString());
        }
    }

    private void runServerOne() throws Exception {
        DatabaseManager database = ItemGuard.getInstance().getDB();
        ItemData item = new ItemData(CODE, ITEM_UUID);
        item.setCreatedAt(NOW);
        item.setLastSeenAt(NOW);
        item.setLastAction("PROBE_SEED");
        database.saveItem(item);
        database.flush();

        database.recordObservation(observation(SERVER_ONE_EPOCH, "server-1-player", 0, NOW - 60_000));
        database.recordObservation(observation(SERVER_ONE_EPOCH, "server-1-staff", 1, NOW - 60_000));
        database.flush();

        List<ItemObservation> sameServerRows = readObservations(database, SERVER_ONE_EPOCH);
        DuplicateAssessment sameServer = new DuplicateDetector().assess(
            ITEM_UUID,
            SERVER_ONE_EPOCH,
            true,
            sameServerRows
        );
        if (!"CONFIRMED".equals(sameServer.status().name())) {
            throw new IllegalStateException("same-server duplicate status=" + sameServer.status());
        }
        writeReceipt("READY", "same_server_status=CONFIRMED, server_id=server-1");
        getLogger().info("PREMIUM_TWO_SERVER_PROBE READY server-1 same_server=CONFIRMED");
        // Keep server-1 alive while server-2 performs its shared-database observation. The runner
        // owns the eventual stop so the two Paper processes overlap in the controlled fixture.
        while (isEnabled()) {
            Thread.sleep(250);
        }
    }

    private void runServerTwo() throws Exception {
        DatabaseManager database = ItemGuard.getInstance().getDB();
        waitForSeed(database);
        database.recordObservation(observation(SERVER_TWO_EPOCH, "server-2-player", 0, NOW - 30_000));
        database.flush();

        List<CrossServerSighting> sightings = readSightings(database);
        CrossServerAssessment assessment = new CrossServerFindingPolicy()
            .assess(ITEM_UUID, NOW, 30 * 60_000L, sightings);
        if (assessment.status() != CrossServerStatus.SEEN_ON_MULTIPLE_SERVERS) {
            throw new IllegalStateException("cross-server status=" + assessment.status());
        }
        if (!assessment.servers().equals(List.of("server-1", "server-2"))) {
            throw new IllegalStateException("cross-server servers=" + assessment.servers());
        }
        String statement = assessment.statement().toLowerCase();
        if (statement.contains("duplicate") || statement.contains("copy") || statement.contains("dupe")) {
            throw new IllegalStateException("cross-server statement used forbidden duplicate language");
        }
        writeReceipt("PASS", "cross_server_status=SEEN_ON_MULTIPLE_SERVERS, servers=server-1|server-2");
        getLogger().info(
            "PREMIUM_TWO_SERVER_PROBE PASS server-2 cross_server=SEEN_ON_MULTIPLE_SERVERS servers=server-1|server-2"
        );
    }

    private void waitForSeed(DatabaseManager database) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            boolean present = database.getConnectionOwner().call(connection -> {
                try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM tracked_items WHERE code = ?")) {
                    statement.setString(1, CODE);
                    try (ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        return rows.getInt(1) == 1;
                    }
                }
            });
            if (present) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("server-1 seed was not visible within 60 seconds");
    }

    private ItemObservation observation(long epoch, String holder, int slot, long observedAt) {
        return new ItemObservation(
            ITEM_UUID,
            CODE,
            epoch,
            new ObservationKey(HolderType.PLAYER, holder, slot),
            observedAt
        );
    }

    private List<ItemObservation> readObservations(DatabaseManager database, long epoch) {
        return database.getConnectionOwner().call(connection -> {
            List<ItemObservation> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                SELECT item_uuid, code, scan_epoch, holder_type, holder_id, slot, observed_at
                FROM item_observations WHERE scan_epoch = ? ORDER BY observation_id
                """)) {
                statement.setLong(1, epoch);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(new ItemObservation(
                            UUID.fromString(rows.getString(1)),
                            rows.getString(2),
                            rows.getLong(3),
                            new ObservationKey(
                                HolderType.valueOf(rows.getString(4)),
                                rows.getString(5),
                                rows.getInt(6)
                            ),
                            rows.getLong(7)
                        ));
                    }
                }
            }
            return result;
        });
    }

    private List<CrossServerSighting> readSightings(DatabaseManager database) {
        return database.getConnectionOwner().call(connection -> {
            List<CrossServerSighting> result = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                     "SELECT item_uuid, server_id, observed_at FROM item_observations ORDER BY observation_id")) {
                while (rows.next()) {
                    result.add(new CrossServerSighting(
                        UUID.fromString(rows.getString(1)),
                        rows.getString(2),
                        rows.getLong(3),
                        false
                    ));
                }
            }
            return result;
        });
    }

    private void fail(String detail) {
        getLogger().severe("PREMIUM_TWO_SERVER_PROBE FAIL " + serverId + " " + detail);
        try {
            writeReceipt("FAIL", detail);
        } catch (IOException ignored) {
        }
    }

    private void writeReceipt(String status, String detail) throws IOException {
        Files.createDirectories(receipt.getParent());
        Files.writeString(
            receipt,
            "{\"status\":\"" + status + "\",\"server_id\":\"" + serverId
                + "\",\"detail\":\"" + detail.replace("\"", "'") + "\"}\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }
}
