package premiumfailure;

import com.itemguard.ItemGuard;
import com.itemguard.data.DatabaseManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.ResultSet;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Fixture-only Paper probe for Premium fail-closed runtime semantics. */
public final class PremiumFailureProbe extends JavaPlugin {
    private Path receipt;

    @Override
    public void onEnable() {
        receipt = getDataFolder().toPath().resolve("failure-receipt.json");
        getCommand("failureprobe").setExecutor(this);
        getServer().getScheduler().runTaskAsynchronously(this, this::announceReady);
    }

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] args
    ) {
        if (args.length != 1) {
            sender.sendMessage("usage: /failureprobe <baseline|outage-write|recovery-read>");
            return true;
        }
        if ("baseline".equalsIgnoreCase(args[0])) {
            commitBaseline();
            return true;
        }
        if ("outage-write".equalsIgnoreCase(args[0])) {
            outageWrite();
            return true;
        }
        if ("recovery-read".equalsIgnoreCase(args[0])) {
            recoveryRead();
            return true;
        }
        sender.sendMessage("unknown failure probe operation");
        return true;
    }

    private void announceReady() {
        try {
            int duplicates = readDuplicates();
            writeReceipt("READY", "duplicates_detected=" + duplicates);
            getLogger().info("PREMIUM_FAILURE_PROBE READY duplicates_detected=" + duplicates);
        } catch (Throwable failure) {
            fail("READY_FAILED", failure);
        }
    }

    private void commitBaseline() {
        CompletableFuture<Integer> operation = ItemGuard.getInstance().getDB()
            .getConnectionOwner()
            .callAsync(connection -> {
                try (var statement = connection.prepareStatement(
                    "UPDATE plugin_stats SET duplicates_detected = 7 WHERE id = 1")) {
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement(
                    "SELECT duplicates_detected FROM plugin_stats WHERE id = 1")) {
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new IllegalStateException("plugin_stats row id=1 is missing");
                        }
                        return rows.getInt(1);
                    }
                }
            });
        operation.whenComplete((duplicates, failure) -> {
            if (failure != null) {
                fail("BASELINE_FAILED", failure);
                return;
            }
            if (duplicates == null || duplicates != 7) {
                fail("BASELINE_UNEXPECTED_VALUE", new IllegalStateException(
                    "expected duplicates_detected=7 but got " + duplicates));
                return;
            }
            writeReceiptSafely("BASELINE_COMMITTED", "duplicates_detected=7");
            getLogger().info("PREMIUM_FAILURE_PROBE BASELINE_COMMITTED duplicates_detected=7");
        });
    }

    private void outageWrite() {
        getLogger().info("PREMIUM_FAILURE_PROBE OUTAGE_WRITE_STARTED");
        DatabaseManager database = ItemGuard.getInstance().getDB();
        CompletableFuture<Void> operation = database.getConnectionOwner().callAsync(connection -> {
            try (var delay = connection.createStatement()) {
                delay.execute("SELECT SLEEP(5)");
            }
            try (var statement = connection.prepareStatement(
                "UPDATE plugin_stats SET duplicates_detected = duplicates_detected + 100 WHERE id = 1")) {
                statement.executeUpdate();
            }
            return null;
        });

        operation.whenComplete((ignored, failure) -> {
            if (failure == null) {
                fail("OUTAGE_WRITE_UNEXPECTED_SUCCESS", null);
                return;
            }
            writeReceiptSafely("OUTAGE_WRITE_FAILED", failure);
            getLogger().info(
                "PREMIUM_FAILURE_PROBE OUTAGE_WRITE_FAILED failure="
                    + failure.getClass().getName()
            );
        });
    }

    private void recoveryRead() {
        try {
            int duplicates = readDuplicates();
            if (duplicates != 7) {
                throw new IllegalStateException("unexpected duplicates_detected=" + duplicates);
            }
            writeReceipt("RECOVERY_READ_PASS", "duplicates_detected=7");
            getLogger().info("PREMIUM_FAILURE_PROBE RECOVERY_READ_PASS duplicates_detected=7");
            getServer().getScheduler().runTaskLaterAsynchronously(this, () -> {
                try {
                    int stable = readDuplicates();
                    if (stable != 7) {
                        throw new IllegalStateException(
                            "unexpected delayed duplicates_detected=" + stable);
                    }
                    writeReceipt("RECOVERY_STABLE_PASS", "duplicates_detected=7");
                    getLogger().info(
                        "PREMIUM_FAILURE_PROBE RECOVERY_STABLE_PASS duplicates_detected=7");
                } catch (Throwable failure) {
                    fail("RECOVERY_STABLE_FAILED", failure);
                }
            }, 60L);
        } catch (Throwable failure) {
            fail("RECOVERY_READ_FAILED", failure);
        }
    }

    private int readDuplicates() {
        return ItemGuard.getInstance().getDB().getConnectionOwner().call(connection -> {
            try (var statement = connection.prepareStatement(
                "SELECT duplicates_detected FROM plugin_stats WHERE id = 1")) {
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException("plugin_stats row id=1 is missing");
                    }
                    return rows.getInt(1);
                }
            }
        });
    }

    private void fail(String status, Throwable failure) {
        writeReceiptSafely(status, failure);
        getLogger().severe(
            "PREMIUM_FAILURE_PROBE " + status
                + (failure == null ? "" : " failure=" + failure.getClass().getName())
        );
    }

    private void writeReceiptSafely(String status, Throwable failure) {
        writeReceiptSafely(
            status,
            failure == null ? "" : "failure=" + failure.getClass().getName()
        );
    }

    private void writeReceiptSafely(String status, String detail) {
        try {
            writeReceipt(status, detail);
        } catch (IOException ignored) {
        }
    }

    private void writeReceipt(String status, String detail) throws IOException {
        Files.createDirectories(receipt.getParent());
        Files.writeString(
            receipt,
            "{\"status\":\"" + status + "\",\"detail\":\""
                + detail.replace("\"", "'") + "\"}\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }
}
