package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.persistence.MigrationReport;
import com.itemguard.persistence.MySqlConnectionOwner;
import com.itemguard.persistence.MySqlMigrationService;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;

/** Admin-only, asynchronous, dry-run-first SQLite to MySQL migration command. */
public final class MigrateCommand {
    private final ItemGuard plugin;

    public MigrateCommand(ItemGuard plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, boolean confirm) {
        String url = plugin.getConfigs().getMySqlUrl().trim();
        String user = plugin.getConfigs().getMySqlUser().trim();
        String password = plugin.getConfigs().getMySqlPassword();
        if (url.isEmpty() || user.isEmpty() || password.isEmpty()) {
            sender.sendMessage("§cItemGuard migration is not configured. Set database.mysql.url, "
                + "database.mysql.user and database.mysql.password first.");
            return;
        }
        String serverId = plugin.getConfigs().getServerId().trim();
        if (serverId.isEmpty()) {
            sender.sendMessage("§cSet multi-server.server-id before migrating; a migrated "
                + "observation must name its source server.");
            return;
        }
        Path source = new File(plugin.getDataFolder(), plugin.getConfigs().getSqliteFileName()).toPath();
        sender.sendMessage(confirm
            ? "§eItemGuard migration is checking the source and target before copying..."
            : "§eItemGuard migration dry-run is checking the source and target...");
        CompletableFuture.runAsync(() -> run(sender, confirm, url, user, password, serverId, source))
            .exceptionally(failure -> {
                plugin.getLogger().log(Level.SEVERE, "ItemGuard migration failed", failure);
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(
                    "§cItemGuard migration failed closed. The SQLite source was not modified. "
                        + "See the server log for the JDBC reason."));
                return null;
            });
    }

    private void run(CommandSender sender, boolean confirm, String url, String user, String password,
                     String serverId, Path source) {
        try (MySqlConnectionOwner owner = new MySqlConnectionOwner(
            MySqlConnectionOwner.hikariDataSource(
                url,
                user,
                password,
                plugin.getConfigs().getPoolMaxSize(),
                plugin.getConfigs().getPoolMinIdle(),
                plugin.getConfigs().getPoolConnectionTimeout()),
            plugin.getConfigs().getPoolMaxSize(),
            failure -> plugin.getLogger().log(Level.SEVERE, "ItemGuard migration database failure", failure))) {
            MySqlMigrationService service = new MySqlMigrationService(owner, source, serverId);
            MigrationReport plan = service.dryRun();
            if (!confirm) {
                sendReport(sender, plan, false);
                return;
            }
            if (!plan.targetEmpty()) {
                throw new IllegalStateException(
                    "Migration target is not empty; refusing merge or overwrite");
            }
            MigrationReport result = service.migrate();
            sendReport(sender, result, true);
        }
    }

    private void sendReport(CommandSender sender, MigrationReport report, boolean executed) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String mode = executed ? "completed" : "dry-run";
            sender.sendMessage("§aItemGuard migration " + mode + ": source rows="
                + summarize(report.sourceRows()) + ", target rows=" + summarize(report.targetRows())
                + ". Source SQLite was not deleted.");
            if (!executed) {
                sender.sendMessage("§eNo rows were copied. Review the counts, then run "
                    + "/ig migrate confirm.");
            } else if (report.verified()) {
                sender.sendMessage("§aRow-count verification passed and server_id was stamped "
                    + "from multi-server.server-id.");
            }
        });
    }

    private String summarize(Map<String, Long> rows) {
        return rows.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(", "));
    }
}
