package com.itemguard;

import com.itemguard.storage.DatabaseSizeAdvisor;
import com.itemguard.api.ItemGuardAPI;
import com.itemguard.catalog.CatalogUi;
import com.itemguard.commands.*;
import com.itemguard.config.ReloadDecision;
import com.itemguard.config.ReloadPolicy;
import com.itemguard.config.ReloadStatus;
import com.itemguard.config.RestartSensitiveSettings;
import com.itemguard.data.DatabaseManager;
import com.itemguard.gui.FilterChatListener;
import com.itemguard.gui.GUIListener;
import com.itemguard.integrations.DiscordWebhook;
import com.itemguard.integrations.VaultHook;
import com.itemguard.integrations.WorldGuardHook;
import com.itemguard.listeners.*;

import com.itemguard.services.ItemTrackingService;
import com.itemguard.search.FindItemService;
import com.itemguard.tasks.CleanupTask;
import com.itemguard.tasks.InventoryScanTask;
import com.itemguard.tasks.InventoryScanScheduler;
import com.itemguard.tasks.ScanMetrics;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;



import java.io.File;

public class ItemGuard extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 34029;
    private static ItemGuard instance;
    private DatabaseManager databaseManager;
    private com.itemguard.listeners.ItemLossListener lossListener;
    private ItemTrackingService itemTrackingService;
    private ConfigManager configManager;
    private MessageManager messageManager;
    private GUIListener guiListener;
    private CatalogUi catalogUi;
    private FilterChatListener filterChatListener;
    private InventoryScanTask inventoryScanTask;
    private InventoryScanScheduler.ScheduledTask scheduledInventoryScan;
    private CleanupTask cleanupTask;
    private VaultHook vaultHook;
    private WorldGuardHook worldGuardHook;
    private DiscordWebhook discordWebhook;
    private Metrics metrics;



    private ItemGuardAPI api;
    private FindItemService findItemService;
    private final ScanMetrics scanMetrics = new ScanMetrics();
    private final ReloadPolicy reloadPolicy = new ReloadPolicy();

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        loadConfigManagers();

        // M5 (review 2026-09-17): `general.enabled` is documented as "Enable/disable the entire
        // plugin" and was read by nothing. An admin who hit a problem and did what the config told
        // them to — set it to false, restart — saw no change at all: listeners, sweeps, click
        // cancellations and the database all kept running. A switch that does nothing costs them
        // the one recovery step they had.
        //
        // The check sits here, between the two halves of what used to be `loadManagers`, because the
        // database half opens the SQLite file, takes the single-owner sidecar lock and runs startup
        // claim recovery. Being "disabled" while still holding that lock is not what the key says,
        // and the first version of this guard claimed in its log line that no database work ran
        // while the database was already open.
        if (!configManager.isEnabled()) {
            getLogger().info(
                "general.enabled is false in config.yml: ItemGuard will not start. No listener, "
                    + "sweep, command, hook or database work runs and the database file is not "
                    + "opened. Set it to true and restart to enable it.");
            // M6 (review 2026-09-17): returning from onEnable does not disable the plugin — Bukkit has
            // already marked it enabled, so another plugin that checks isPluginEnabled() and then
            // calls getApi() gets a NullPointerException inside its own stack trace, pointing at the
            // wrong plugin. Disabling makes the state honest, and onDisable is null-safe for a plugin
            // that never opened anything.
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadRuntimeManagers();
        warnAboutIgnoredAntiDupeAction();
        registerHooks();
        registerListeners();
        registerCommands();
        scheduleTasks();
        registerMetrics();



        getLogger().info("===========================================");
        getLogger().info("  ItemGuard v" + getDescription().getVersion() + " is enabled.");
        getLogger().info("  Anti-Dupe & Item Tracking Plugin");
        // Report the server actually running us, not a hardcoded number. The old line said
        // "Minecraft: 1.21.11" on every server, so an admin on 1.21.4 read it as a
        // compatibility warning and had every reason to file a bug.
        getLogger().info("  Server: " + getServer().getVersion());
        getLogger().info("===========================================");
    }

    @Override
    public void onDisable() {
        if (catalogUi != null) catalogUi.clear();
        if (guiListener != null) {
            guiListener.clearSessions();
        }
        if (filterChatListener != null) {
            filterChatListener.clearSessions();
        }
        if (scheduledInventoryScan != null) {
            scheduledInventoryScan.cancel();
        }
        if (inventoryScanTask != null) {
            inventoryScanTask.cancelSweepTicker();
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        // Before the database closes: stops the scan and revokes every queued loss, so nothing is
        // still trying to write when the connection goes and no callback hops back into a server
        // that is shutting down.
        if (lossListener != null) {
            lossListener.stop();
        }

        if (databaseManager != null) {
            databaseManager.close();
        }

        getLogger().info("ItemGuard is disabled.");
    }

    private void registerHooks() {
        this.vaultHook = new VaultHook(this);
        this.worldGuardHook = new WorldGuardHook(this);
        this.discordWebhook = new DiscordWebhook(this);

    }

    private void registerMetrics() {
        metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("edition", () -> isLiteEdition() ? "lite" : "full"));
        metrics.addCustomChart(new SimplePie("language", () -> getConfigs().getLanguage()));
        metrics.addCustomChart(new SimplePie("anti_dupe_mode", () -> getConfigs().getAntiDupeAction()));
    }




    /**
     * The half of startup that reads configuration.
     *
     * <p>Separated from {@link #loadRuntimeManagers()} so {@code general.enabled} can be honoured
     * before the database is opened: reading the switch requires the config, and respecting it must
     * not require everything the switch is supposed to turn off.
     */
    private void loadConfigManagers() {
        this.configManager = new ConfigManager(this);
        this.messageManager = new MessageManager(this);
    }

    /** The half of startup that opens state and wires collaborators up to it. */
    private void loadRuntimeManagers() {
        this.databaseManager = new DatabaseManager(this);
        this.itemTrackingService = new ItemTrackingService(this);
        this.findItemService = new FindItemService(
            databaseManager,
            code -> databaseManager.getItem(code).isPresent(),
            System::currentTimeMillis
        );
        this.api = new ItemGuardAPI(this);
    }

    private void registerListeners() {
        // CatalogUi and the GUI stack are FULL-only and resolve Adventure (net.kyori) colour
        // constants during class initialisation. Paper bundles Adventure; Spigot does not, so
        // on Spigot merely constructing CatalogUi throws NoClassDefFoundError and the whole
        // plugin fails to enable — verified on a real Spigot 1.21.4 server, 2026-09-15.
        // LITE never exposes these surfaces, so skip building them entirely rather than
        // relying on them being unused.
        if (!isLiteEdition()) {
            this.catalogUi = new CatalogUi(this);
            getServer().getPluginManager().registerEvents(catalogUi, this);
            this.guiListener = new GUIListener(this);
            getServer().getPluginManager().registerEvents(guiListener, this);
            this.filterChatListener = new FilterChatListener(this);
            getServer().getPluginManager().registerEvents(filterChatListener, this);
            guiListener.setFilterChatListener(filterChatListener);
        }
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new ContainerListener(this), this);
        getServer().getPluginManager().registerEvents(new ItemListener(this), this);
        // Tagging an item the instant it enters the world needs a Paper-only event. Spigot
        // does not have it, and Bukkit refuses a whole listener class over one missing event
        // type — which is why this lives in its own class rather than inside ItemListener.
        // Without it, items are tagged on first pickup or container scan instead of at spawn.
        if (com.itemguard.listeners.PaperEntitySpawnListener.isAvailable()) {
            getServer().getPluginManager().registerEvents(
                new com.itemguard.listeners.PaperEntitySpawnListener(this), this);
        } else {
            getLogger().info(
                "Spawn-time item tagging is unavailable on this server (Paper-only event); "
                    + "items are tagged on first pickup or container scan instead.");
        }
        // Records why a tracked item stopped existing: burned, despawned, or removed by /clear.
        this.lossListener = new com.itemguard.listeners.ItemLossListener(this);
        // The inventory watch runs on the tick, so its history read and loss write go through the
        // database's own executor instead. The dispatcher is the way back: a scheduler that refuses
        // once the plugin is disabled, which the coordinator reads as "this entry is over".
        lossListener.useOffload(new com.itemguard.listeners.ItemLossScanOffloadCoordinator(
            new com.itemguard.persistence.SqliteLossJournal(
                databaseManager.getConnectionOwner(),
                System::currentTimeMillis,
                databaseManager.getServerId()
            ),
            task -> getServer().getScheduler().runTask(this, task),
            this::isEnabled,
            new com.itemguard.dupe.ScanEpochGenerator()
        ));
        getServer().getPluginManager().registerEvents(lossListener, this);
        lossListener.start();
        getServer().getPluginManager().registerEvents(new CraftListener(this), this);
    }

    public CatalogUi getCatalogUi() { return catalogUi; }

    public boolean isLiteEdition() { return false; }

    protected void registerCommands() {
        MainCommand mainCmd = new MainCommand(this);
        getCommand("itemguard").setExecutor(mainCmd);
        getCommand("itemguard").setTabCompleter(mainCmd);
        getCommand("ig").setExecutor(mainCmd);
        getCommand("ig").setTabCompleter(mainCmd);
        getCommand("igcheck").setExecutor(new CheckCommand(this));
        getCommand("igcheck").setTabCompleter(new CheckCommand(this));
        getCommand("ighistory").setExecutor(new HistoryCommand(this));
        getCommand("ighistory").setTabCompleter(new HistoryCommand(this));
        getCommand("igsearch").setExecutor(new SearchCommand(this));
        getCommand("igsearch").setTabCompleter(new SearchCommand(this));
        getCommand("igstats").setExecutor(new StatsCommand(this));
        getCommand("igstats").setTabCompleter(new StatsCommand(this));
        FindItemCommand findItemCommand = new FindItemCommand(this, findItemService);
        getCommand("finditem").setExecutor(findItemCommand);
        getCommand("finditem").setTabCompleter(findItemCommand);
        MatDoCommand matDoCommand = new MatDoCommand(this);
        getCommand("matdo").setExecutor(matDoCommand);
        getCommand("matdo").setTabCompleter(matDoCommand);
    }

    /**
     * Says out loud what {@code anti-dupe.action} will not do.
     *
     * <p>The config offers destructive choices and the resolver downgrades all of them to NOTIFY.
     * Ignoring a setting an owner deliberately chose, without a word, is the failure mode this project
     * keeps finding by running the server rather than by reading the code — see
     * {@link com.itemguard.config.DestructiveAntiDupeNotice}.
     */
    private void warnAboutIgnoredAntiDupeAction() {
        new com.itemguard.config.DestructiveAntiDupeNotice()
            .warningFor(configManager.getAntiDupeAction())
            .ifPresent(getLogger()::warning);
    }

    private void scheduleTasks() {
        int scanInterval = configManager.getInventoryScanInterval();
        if (scanInterval > 0) {
            inventoryScanTask = new InventoryScanTask(this, scanMetrics);
            InventoryScanScheduler scheduler = new InventoryScanScheduler(
                (task, delay, period) -> {
                    var bukkitTask = getServer().getScheduler().runTaskTimer(
                        this,
                        task,
                        delay,
                        period
                    );
                    return bukkitTask::cancel;
                }
            );
            scheduledInventoryScan = scheduler.schedule(inventoryScanTask, scanInterval);
        }

        int cleanupHours = configManager.getCleanupIntervalHours();
        if (cleanupHours > 0) {
            cleanupTask = new CleanupTask(this);
            cleanupTask.runTaskTimer(this, cleanupHours * 72000L, cleanupHours * 72000L);
        } else {
            // No cleanup task means the database only grows. That is the intended LITE
            // behaviour, but it must not be a surprise six months later, so watch the file
            // and say something once. Hourly is often enough for a number that moves slowly,
            // and the check is a single file-size read off the main thread.
            scheduleDatabaseSizeAdvisory();
        }
    }

    private void scheduleDatabaseSizeAdvisory() {
        long threshold = configManager.getDatabaseWarnBytes();
        if (threshold <= 0) {
            return;
        }
        DatabaseSizeAdvisor advisor = new DatabaseSizeAdvisor(threshold);
        java.io.File databaseFile = new java.io.File(getDataFolder(), configManager.getSqliteFileName());
        getServer().getScheduler().runTaskTimerAsynchronously(
            this,
            () -> {
                if (!databaseFile.isFile()) {
                    return;
                }
                advisor.review(databaseFile.length())
                    .ifPresent(message -> getLogger().warning(message));
            },
            20L * 60,        // first check a minute in, once the file exists
            20L * 60 * 60    // then hourly
        );
    }

    public void saveData() {
        if (databaseManager != null) {
            databaseManager.flush();
        }
    }

    public ReloadDecision reload() {
        File configFile = new File(getDataFolder(), "config.yml");
        RestartSensitiveSettings current = configManager.getRestartSensitiveSettings();
        RestartSensitiveSettings candidate = RestartSensitiveSettings.from(
            YamlConfiguration.loadConfiguration(configFile)
        );
        ReloadDecision decision = reloadPolicy.evaluate(current, candidate);
        if (decision.status() == ReloadStatus.RESTART_REQUIRED) {
            return decision;
        }

        reloadConfig();
        configManager.reload();
        messageManager.reload();
        return decision;
    }

    public static ItemGuard getInstance() {
        return instance;
    }

    public DatabaseManager getDB() {
        return databaseManager;
    }

    public ItemTrackingService getTrackingService() {
        return itemTrackingService;
    }

    public ConfigManager getConfigs() {
        return configManager;
    }

    public MessageManager getMessages() {
        return messageManager;
    }

    public GUIListener getGuiListener() {
        return guiListener;
    }

    /**
     * The observation scan's own numbers, shared with the scheduler that writes them so the admin
     * command reads the live instance rather than an empty copy.
     */
    public ScanMetrics getScanMetrics() {
        return scanMetrics;
    }

    /** The scheduled scan task, or null when scanning is disabled by config. */
    public InventoryScanTask getInventoryScanTask() {
        return inventoryScanTask;
    }

    public NamespacedKey getNamespacedKey(String key) {
        return new NamespacedKey(this, key);
    }

    public VaultHook getVaultHook() {
        return vaultHook;
    }

    public WorldGuardHook getWorldGuardHook() {
        return worldGuardHook;
    }

    public DiscordWebhook getDiscordWebhook() {
        return discordWebhook;
    }

    public ItemGuardAPI getApi() {
        return api;
    }
}
