package com.itemguard.metrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.itemguard.ItemGuard;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

public class Metrics {

    private final ItemGuard plugin;
    private final Map<String, Chart> charts = new LinkedHashMap<>();
    private final Thread scheduler;
    private volatile boolean shutdown = false;

    private static final String BSTATS_URL = "https://bStats.org/api/v2/plugins/%s/submit";
    private static final int PLUGIN_ID = 26567;

    public Metrics(ItemGuard plugin) {
        this.plugin = plugin;
        startSubmitting();
    }

    public void addCustomChart(CustomChart chart) {
        if (chart == null) return;
        charts.put(chart.getKey(), chart);
    }

    private void startSubmitting() {
        scheduler = new Thread(() -> {
            while (!shutdown) {
                if (shouldSubmit()) {
                    submitData();
                }
                try {
                    Thread.sleep(1000 * 60 * 5);
                } catch (InterruptedException ignored) {}
            }
        }, "ItemGuard-bStats");
        scheduler.setDaemon(true);
        scheduler.start();
    }

    private boolean shouldSubmit() {
        try {
            File file = new File(new File(plugin.getDataFolder().getParent(), "bStats"),
                "config.yml");
            if (!file.exists()) return true;
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            if (!config.contains("enabled", true)) return true;
            return config.getBoolean("enabled", true);
        } catch (Exception e) {
            return true;
        }
    }

    private void submitData() {
        JsonObject data = new JsonObject();
        data.addProperty("schemaVersion", 2);
        data.addProperty("serviceVersion", plugin.getDescription().getVersion());

        JsonObject pluginData = new JsonObject();
        pluginData.addProperty("pluginId", PLUGIN_ID);
        pluginData.addProperty("enabled", plugin.getConfigs().isEnabled());
        pluginData.addProperty("serverUUID", getServerUUID());
        pluginData.addProperty("playerCount", Bukkit.getOnlinePlayers().size());
        pluginData.addProperty("serverVersion", Bukkit.getVersion());
        pluginData.addProperty("javaVersion", System.getProperty("java.version"));

        JsonArray chartData = new JsonArray();
        for (Map.Entry<String, Chart> entry : charts.entrySet()) {
            try {
                JsonObject chart = entry.getValue().getRequestJsonObject();
                if (chart != null) {
                    chartData.add(chart);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                    "Failed to get chart data for " + entry.getKey() + ": " + e.getMessage());
            }
        }
        pluginData.add("charts", chartData);
        data.add("plugin", pluginData);

        try {
            String json = data.toString();
            URL url = new URL(String.format(BSTATS_URL, PLUGIN_ID));
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", "ItemGuard-bStats/1.0");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != 200 && code != 202) {
                plugin.getLogger().warning("bStats returned code: " + code);
            }
            conn.disconnect();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not submit bStats data: " + e.getMessage());
        }
    }

    private String getServerUUID() {
        File file = new File(plugin.getDataFolder().getParent(), "bStats");
        File uuidFile = new File(file, "uuid.txt");
        if (uuidFile.exists()) {
            try {
                return new String(java.nio.file.Files.readAllBytes(uuidFile.toPath()), StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) {}
        }
        String uuid = UUID.randomUUID().toString();
        try {
            file.mkdirs();
            java.nio.file.Files.writeString(uuidFile.toPath(), uuid, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return uuid;
    }

    public void shutdown() {
        shutdown = true;
        if (scheduler != null) {
            scheduler.interrupt();
        }
    }

    public abstract static class Chart {
        public abstract String getKey();
        public abstract JsonObject getRequestJsonObject();
    }

    public static class SimplePie extends Chart {
        private final String key;
        private final Callable<String> callable;

        public interface Callable<V> { V call() throws Exception; }

        public SimplePie(String key, Callable<String> callable) {
            this.key = key;
            this.callable = callable;
        }

        @Override
        public String getKey() { return key; }

        @Override
        public JsonObject getRequestJsonObject() {
            JsonObject json = new JsonObject();
            json.addProperty("type", "simple");
            json.addProperty("name", key);
            String value;
            try {
                value = callable.call();
            } catch (Exception e) {
                value = "unknown";
            }
            json.addProperty("value", value);
            return json;
        }
    }

    public static class SingleLineChart extends Chart {
        private final String key;
        private final Callable<Integer> callable;

        public interface Callable<V> { V call() throws Exception; }

        public SingleLineChart(String key, Callable<Integer> callable) {
            this.key = key;
            this.callable = callable;
        }

        @Override
        public String getKey() { return key; }

        @Override
        public JsonObject getRequestJsonObject() {
            JsonObject json = new JsonObject();
            json.addProperty("type", "single");
            json.addProperty("name", key);
            int value;
            try {
                value = callable.call();
            } catch (Exception e) {
                value = -1;
            }
            json.addProperty("value", value);
            return json;
        }
    }
}
