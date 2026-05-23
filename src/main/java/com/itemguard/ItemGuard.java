package com.itemguard;

import com.itemguard.api.ItemGuardAPI;
import com.itemguard.commands.*;
import com.itemguard.data.DatabaseManager;
import com.itemguard.gui.GUIListener;
import com.itemguard.integrations.DiscordWebhook;
import com.itemguard.integrations.VaultHook;
import com.itemguard.integrations.WorldGuardHook;
import com.itemguard.listeners.*;
import com.itemguard.metrics.ItemGuardMetrics;
import com.itemguard.services.ItemTrackingService;
import com.itemguard.tasks.CleanupTask;
import com.itemguard.tasks.InventoryScanTask;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public class ItemGuard extends JavaPlugin {

    private static ItemGuard instance;
    private DatabaseManager databaseManager;
    private ItemTrackingService itemTrackingService;
    private ConfigManager configManager;
    private MessageManager messageManager;
    private GUIListener guiListener;
    private InventoryScanTask inventoryScanTask;
    private CleanupTask cleanupTask;
    private VaultHook vaultHook;
    private WorldGuardHook worldGuardHook;
    private DiscordWebhook discordWebhook;
    private ItemGuardMetrics metrics;
    private ItemGuardAPI api;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        loadManagers();
        registerHooks();
        registerListeners();
        registerCommands();
        scheduleTasks();

        getLogger().info("===========================================");
        getLogger().info("  ItemGuard v" + getDescription().getVersion() + " da kich hoat!");
        getLogger().info("  Anti-Dupe & Item Tracking Plugin");
        getLogger().info("  Minecraft: 1.21.11");
        getLogger().info("===========================================");
    }

    @Override
    public void onDisable() {
        saveData();

        if (inventoryScanTask != null) {
            inventoryScanTask.cancel();
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        if (metrics != null) {
            metrics.shutdown();
        }

        if (databaseManager != null) {
            databaseManager.close();
        }

        getLogger().info("ItemGuard da tat!");
    }

    private void registerHooks() {
        this.vaultHook = new VaultHook(this);
        this.worldGuardHook = new WorldGuardHook(this);
        this.discordWebhook = new DiscordWebhook(this);
        this.metrics = new ItemGuardMetrics(this);
    }

    private void loadManagers() {
        this.configManager = new ConfigManager(this);
        this.messageManager = new MessageManager(this);
        this.databaseManager = new DatabaseManager(this);
        this.itemTrackingService = new ItemTrackingService(this);
        this.api = new ItemGuardAPI(this);
    }

    private void registerListeners() {
        this.guiListener = new GUIListener();
        getServer().getPluginManager().registerEvents(guiListener, this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new ContainerListener(this), this);
        getServer().getPluginManager().registerEvents(new ItemListener(this), this);
        getServer().getPluginManager().registerEvents(new CraftListener(this), this);
    }

    private void registerCommands() {
        getCommand("itemguard").setExecutor(new MainCommand(this));
        getCommand("itemguard").setTabCompleter(new MainCommand(this));
        getCommand("igcheck").setExecutor(new CheckCommand(this));
        getCommand("igcheck").setTabCompleter(new CheckCommand(this));
        getCommand("ighistory").setExecutor(new HistoryCommand(this));
        getCommand("ighistory").setTabCompleter(new HistoryCommand(this));
        getCommand("igsearch").setExecutor(new SearchCommand(this));
        getCommand("igsearch").setTabCompleter(new SearchCommand(this));
        getCommand("igstats").setExecutor(new StatsCommand(this));
        getCommand("igstats").setTabCompleter(new StatsCommand(this));
    }

    private void scheduleTasks() {
        int scanInterval = configManager.getInventoryScanInterval();
        if (scanInterval > 0) {
            inventoryScanTask = new InventoryScanTask(this);
            inventoryScanTask.runTaskTimer(this, scanInterval, scanInterval);
        }

        int cleanupHours = configManager.getCleanupIntervalHours();
        if (cleanupHours > 0) {
            cleanupTask = new CleanupTask(this);
            cleanupTask.runTaskTimer(this, cleanupHours * 72000L, cleanupHours * 72000L);
        }
    }

    public void saveData() {
        if (databaseManager != null) {
            databaseManager.flush();
        }
    }

    public void reload() {
        reloadConfig();
        configManager.reload();
        messageManager.reload();
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
