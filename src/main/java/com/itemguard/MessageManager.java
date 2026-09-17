package com.itemguard;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class MessageManager {

    private final ItemGuard plugin;
    private FileConfiguration messages;
    private File messagesFile;
    private String lang;

    private static final Map<String, String> DEFAULT_MESSAGES = new HashMap<>();

    static {
        // English, and identical to messages_en.yml on purpose: this is the fallback for a key the
        // message file does not define, and it used to be a Vietnamese map compiled into the class.
        // A jar advertised as English-only must not be able to answer a player in another language
        // because someone added a key to the code and not to the file. MessageManagerFallbackTest
        // pins the two together so they cannot drift.
        DEFAULT_MESSAGES.put("prefix", "&8[&eItemGuard&8] &7");
        DEFAULT_MESSAGES.put("reload-success", "Plugin reloaded successfully!");
        DEFAULT_MESSAGES.put("reload-fail", "Failed to reload plugin!");
        DEFAULT_MESSAGES.put("no-permission", "You don't have permission to use this command!");
        DEFAULT_MESSAGES.put("player-only", "This command is for players only!");
        DEFAULT_MESSAGES.put("invalid-args", "Invalid arguments! Usage: &f{usage}");
        DEFAULT_MESSAGES.put("player-not-found", "Player not found: &f{player}");
        DEFAULT_MESSAGES.put("item-not-tracked", "This item is not being tracked!");
        DEFAULT_MESSAGES.put("item-tracked", "Item is now tracked with code: &e{code}");
        DEFAULT_MESSAGES.put("item-info-header", "&6&l=== Item Info ===");
        DEFAULT_MESSAGES.put("item-info-code", "&7Code: &f{code}");
        DEFAULT_MESSAGES.put("item-info-material", "&7Type: &f{material}");
        DEFAULT_MESSAGES.put("item-info-owner", "&7Owner: &f{owner}");
        DEFAULT_MESSAGES.put("item-info-created", "&7Created: &f{time}");
        DEFAULT_MESSAGES.put("item-info-history", "&7History: &f{count} entries");
        DEFAULT_MESSAGES.put("history-header", "&6&l=== Item History ===");
        DEFAULT_MESSAGES.put("history-empty", "No history found!");
        DEFAULT_MESSAGES.put("history-entry", "&7#{num} &8| &f{action} &8| &7{nick} &8| &f{location}");
        DEFAULT_MESSAGES.put("history-time", "&8(&7{time}&8)");
        DEFAULT_MESSAGES.put("search-header", "&6&l=== Search: {player} ===");
        DEFAULT_MESSAGES.put("search-empty", "No items found!");
        DEFAULT_MESSAGES.put("search-result", "&7- &f{item} &8(&7x{amount}&8) &7| Code: &e{code}");
        DEFAULT_MESSAGES.put("stats-header", "&6&l=== ItemGuard Stats ===");
        DEFAULT_MESSAGES.put("stats-total-items", "&7Total items: &f{total}");
        DEFAULT_MESSAGES.put("stats-total-history", "&7Total history: &f{history}");
        DEFAULT_MESSAGES.put("stats-online-tracked", "&7Online tracked: &f{online}");
        DEFAULT_MESSAGES.put("stats-duplicates", "&7Duplicates detected: &f{duplicates}");
        DEFAULT_MESSAGES.put("stats-database", "&7Database: &f{db}");
        DEFAULT_MESSAGES.put("gui-no-history", "&cNo history to display!");
        DEFAULT_MESSAGES.put("gui-click-to-view", "&7Click to view details");
        DEFAULT_MESSAGES.put("action-pickup", "Pickup");
        DEFAULT_MESSAGES.put("action-drop", "Drop");
        DEFAULT_MESSAGES.put("action-inventory", "Inventory");
        DEFAULT_MESSAGES.put("action-container", "Container");
        DEFAULT_MESSAGES.put("action-craft", "Craft");
        DEFAULT_MESSAGES.put("action-use", "Use");
        DEFAULT_MESSAGES.put("action-death", "Death");
        DEFAULT_MESSAGES.put("action-spawn", "Spawn");
        DEFAULT_MESSAGES.put("action-void", "Void");
        DEFAULT_MESSAGES.put("dupe-detected", "&c&l[DUPE ALERT!] &7Item &f{item} &7(Code: &e{code}&7) has multiple copies!");
        DEFAULT_MESSAGES.put("dupe-locations", "&7Locations: &f{locations}");
        // Both added 2026-09-17 (H3/M6 and C2 of that review). Before them, an action cancelled
        // because the identity was not ready said nothing at all, and the craft refusal said it in
        // Vietnamese inside a jar advertised as English.
        DEFAULT_MESSAGES.put("identity-not-ready", "&cItemGuard could not verify this item's identity, so the action was cancelled. Try again in a moment; if it keeps happening, ask staff.");
        // M8 (review 2026-09-17): a corrupt tag is a fixed property of the item, so "try again in a
        // moment" sent the player to repeat an action that can never succeed.
        DEFAULT_MESSAGES.put("identity-corrupt", "&cItemGuard refused this item: its tracking tag is incomplete or unreadable, and retrying will not change that. Staff need to look at it.");
        // M1 (review 2026-09-17): one message served two opposite refusals. This one is a server
        // policy an admin can change, and it says which key changes it; the tagged one below is a
        // copy of an existing identity and has nothing to do with the switch.
        DEFAULT_MESSAGES.put("craft-refused", "&cCraft cancelled: this server's ItemGuard policy blocks crafting a result that would need a new tracked identity. Set tracking.cancel-untracked-craft-output: false in config.yml to allow these crafts.");
        DEFAULT_MESSAGES.put("craft-refused-tagged", "&cCraft cancelled: this recipe would produce an item carrying an identity that is already tracked, which is how a duplicate is made.");
        DEFAULT_MESSAGES.put("craft-refused-tag-pending", "&cCraft cancelled: the result already carries a tracked identity that is not verified yet, so this craft cannot be allowed until it is. Try again in a moment; if it keeps happening, ask staff.");
        DEFAULT_MESSAGES.put("pickup-tagging", "&7ItemGuard is registering this item's identity. Pick it up again in a moment.");
        DEFAULT_MESSAGES.put("check-item-hand", "Please hold an item in your hand!");
        DEFAULT_MESSAGES.put("world-disabled", "Tracking is disabled in this world!");
    }

    public MessageManager(ItemGuard plugin) {
        this.plugin = plugin;
        this.lang = plugin.getConfigs().getLanguage();
        load();
    }

    public void load() {
        String fileName = "messages_" + lang + ".yml";
        messagesFile = new File(plugin.getDataFolder(), fileName);

        if (!messagesFile.exists()) {
            if ("vi".equals(lang)) {
                messagesFile = new File(plugin.getDataFolder(), "messages.yml");
            } else {
                plugin.getDataFolder().mkdirs();
                try {
                    plugin.saveResource(fileName, false);
                    messagesFile = new File(plugin.getDataFolder(), fileName);
                } catch (Exception ignored) {}
            }
        }

        if (!messagesFile.exists()) {
            messagesFile = createDefaultMessages();
        }

        messages = YamlConfiguration.loadConfiguration(messagesFile);

        InputStream defaultStream = plugin.getResource(fileName);
        if (defaultStream != null) {
            YamlConfiguration langDefaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            messages.setDefaults(langDefaults);
        }
    }

    private File createDefaultMessages() {
        try {
            plugin.getDataFolder().mkdirs();
            File file = new File(plugin.getDataFolder(), "messages.yml");
            YamlConfiguration cfg = new YamlConfiguration();

            for (Map.Entry<String, String> entry : DEFAULT_MESSAGES.entrySet()) {
                cfg.set(entry.getKey(), entry.getValue());
            }

            cfg.save(file);
            return file;
        } catch (Exception e) {
            plugin.getLogger().severe("Cannot create default messages file!");
            return new File(plugin.getDataFolder(), "messages.yml");
        }
    }

    public void reload() {
        load();
    }

    public String getRaw(String key) {
        String msg = messages.getString(key, DEFAULT_MESSAGES.getOrDefault(key, key));
        return colorize(msg);
    }

    public String get(String key) {
        return getRaw("prefix") + getRaw(key);
    }

    public String get(String key, Map<String, String> placeholders) {
        String msg = getRaw(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            msg = msg.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return msg;
    }

    public String getRaw(String key, String defaultVal) {
        String msg = messages.getString(key, defaultVal);
        return colorize(msg != null ? msg : defaultVal);
    }

    public String getRaw(String key, Map<String, String> placeholders) {
        String msg = messages.getString(key, DEFAULT_MESSAGES.getOrDefault(key, key));
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            msg = msg.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return colorize(msg);
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(get(key));
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(get(key, placeholders));
    }

    public void sendRaw(CommandSender sender, String key) {
        sender.sendMessage(getRaw(key));
    }

    public void sendRaw(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(getRaw(key, placeholders));
    }

    private String colorize(String msg) {
        if (msg == null) return "";
        return ChatColor.translateAlternateColorCodes('&', msg);
    }
}
