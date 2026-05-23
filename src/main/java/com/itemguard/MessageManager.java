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
        // Vietnamese messages
        DEFAULT_MESSAGES.put("prefix", "&8[&eItemGuard&8] &7");
        DEFAULT_MESSAGES.put("reload-success", "Plugin da duoc tai lai thanh cong!");
        DEFAULT_MESSAGES.put("reload-fail", "Khong the tai lai plugin!");
        DEFAULT_MESSAGES.put("no-permission", "Ban khong co quyen su dung lenh nay!");
        DEFAULT_MESSAGES.put("player-only", "Lenh nay chi danh cho nguoi choi!");
        DEFAULT_MESSAGES.put("invalid-args", "Doi so khong hop le! Su dung: &f{usage}");
        DEFAULT_MESSAGES.put("player-not-found", "Khong tim thay nguoi choi: &f{player}");
        DEFAULT_MESSAGES.put("item-not-tracked", "Vat pham nay chua duoc theo doi!");
        DEFAULT_MESSAGES.put("item-tracked", "Vat pham da duoc theo doi voi ma: &e{code}");
        DEFAULT_MESSAGES.put("item-info-header", "&6&l=== Thong Tin Item ===");
        DEFAULT_MESSAGES.put("item-info-code", "&7Ma so: &f{code}");
        DEFAULT_MESSAGES.put("item-info-material", "&7Loai: &f{material}");
        DEFAULT_MESSAGES.put("item-info-owner", "&7Chu so huu: &f{owner}");
        DEFAULT_MESSAGES.put("item-info-created", "&7Tao luc: &f{time}");
        DEFAULT_MESSAGES.put("item-info-history", "&7Lich su: &f{count} muc");
        DEFAULT_MESSAGES.put("history-header", "&6&l=== Lich Su Item ===");
        DEFAULT_MESSAGES.put("history-empty", "Khong co lich su nao!");
        DEFAULT_MESSAGES.put("history-entry", "&7#{num} &8| &f{action} &8| &7{nick} &8| &f{location}");
        DEFAULT_MESSAGES.put("history-time", "&8(&7{time}&8)");
        DEFAULT_MESSAGES.put("search-header", "&6&l=== Tim Kiem: {player} ===");
        DEFAULT_MESSAGES.put("search-empty", "Khong tim thay vat pham nao!");
        DEFAULT_MESSAGES.put("search-result", "&7- &f{item} &8(&7x{amount}&8) &7| Ma: &e{code}");
        DEFAULT_MESSAGES.put("stats-header", "&6&l=== Thong Ke ItemGuard ===");
        DEFAULT_MESSAGES.put("stats-total-items", "&7Tong so item: &f{total}");
        DEFAULT_MESSAGES.put("stats-total-history", "&7Tong lich su: &f{history}");
        DEFAULT_MESSAGES.put("stats-online-tracked", "&7Dang theo doi online: &f{online}");
        DEFAULT_MESSAGES.put("stats-duplicates", "&7Phat hien dupes: &f{duplicates}");
        DEFAULT_MESSAGES.put("stats-database", "&7Database: &f{db}");
        DEFAULT_MESSAGES.put("gui-no-history", "&cKhong co lich su de hien thi!");
        DEFAULT_MESSAGES.put("gui-click-to-view", "&7Click de xem chi tiet");
        DEFAULT_MESSAGES.put("action-pickup", "Nhat len");
        DEFAULT_MESSAGES.put("action-drop", "Nem ra");
        DEFAULT_MESSAGES.put("action-inventory", "Trong tui");
        DEFAULT_MESSAGES.put("action-container", "Tuong tac");
        DEFAULT_MESSAGES.put("action-craft", "Che bien");
        DEFAULT_MESSAGES.put("action-use", "Su dung");
        DEFAULT_MESSAGES.put("action-death", "Tu vong");
        DEFAULT_MESSAGES.put("action-spawn", "Hien ra");
        DEFAULT_MESSAGES.put("action-void", "Mat khi");
        DEFAULT_MESSAGES.put("dupe-detected", "&c&l[Canh Bao Dupe!] &7Item &f{item} &7(Code: &e{code}&7) co nhieu ban sao!");
        DEFAULT_MESSAGES.put("dupe-locations", "&7Vi tri: &f{locations}");
        DEFAULT_MESSAGES.put("check-item-hand", "Vui long cam vat pham len tay!");
        DEFAULT_MESSAGES.put("world-disabled", "Theo doi da tat o the gioi nay!");
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
