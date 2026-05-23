package com.itemguard.integrations;

import com.itemguard.ItemGuard;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DiscordWebhook {

    private final ItemGuard plugin;
    private final String webhookUrl;
    private final String username;
    private final String avatarUrl;
    private volatile long cooldownUntil = 0;
    private static final long COOLDOWN_MS = 500;

    public DiscordWebhook(ItemGuard plugin) {
        this.plugin = plugin;
        this.webhookUrl = plugin.getConfigs().getDiscordWebhookUrl();
        this.username = plugin.getConfigs().getDiscordWebhookUsername();
        this.avatarUrl = plugin.getConfigs().getDiscordWebhookAvatar();
    }

    public boolean isEnabled() {
        return plugin.getConfigs().isDiscordWebhookEnabled()
            && webhookUrl != null
            && !webhookUrl.isEmpty();
    }

    public void sendDuplicateAlert(String itemName, String code, String holderName, String location) {
        if (!isEnabled()) return;

        if (System.currentTimeMillis() < cooldownUntil) return;
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String payload = buildPayload(
                "\u26A0\uFE0F **DUPLICATE ITEM DETECTED**",
                "Item: **" + itemName + "** (`" + code + "`)\n" +
                "Holder: `" + holderName + "`\n" +
                "Location: `" + location + "`",
                15158332
            );
            send(payload);
        });
    }

    public void send(String title, String description, int color) {
        if (!isEnabled()) return;
        String payload = buildPayload(title, description, color);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> send(payload));
    }

    private String buildPayload(String title, String description, int color) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"username\": \"").append(escapeJson(username)).append("\",");
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            sb.append("\"avatar_url\": \"").append(escapeJson(avatarUrl)).append("\",");
        }
        sb.append("\"embeds\": [{");
        sb.append("\"title\": \"").append(escapeJson(title)).append("\",");
        sb.append("\"description\": \"").append(escapeJson(description)).append("\",");
        sb.append("\"color\": ").append(color).append(",");
        sb.append("\"footer\": {\"text\": \"ItemGuard v").append(plugin.getDescription().getVersion()).append("\"},");
        sb.append("\"timestamp\": \"").append(java.time.Instant.now().toString()).append("\"");
        sb.append("}]}");
        return sb.toString();
    }

    private void send(String payload) {
        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "ItemGuard-Webhook");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            int response = conn.getResponseCode();
            if (response != 204 && response != 200) {
                plugin.getLogger().warning("[Discord] Webhook returned status: " + response);
            }
            conn.disconnect();
        } catch (Exception e) {
            plugin.getLogger().warning("[Discord] Failed to send webhook: " + e.getMessage());
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
