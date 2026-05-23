package com.itemguard;

import com.itemguard.metrics.Metrics;

public class ItemGuardMetrics {

    private final Metrics metrics;

    public ItemGuardMetrics(ItemGuard plugin) {
        this.metrics = new Metrics(plugin);
        registerCharts(plugin);
    }

    private void registerCharts(ItemGuard plugin) {
        metrics.addCustomChart(new Metrics.SimplePie("database_type", () ->
            plugin.getConfigs().getDatabaseType()));

        metrics.addCustomChart(new Metrics.SimplePie("language", () ->
            plugin.getConfigs().getLanguage()));

        metrics.addCustomChart(new Metrics.SingleLineChart("tracked_items", () ->
            plugin.getDB().getStats().getTotalItems()));

        metrics.addCustomChart(new Metrics.SimplePie("anti_dupe_enabled", () ->
            String.valueOf(plugin.getConfigs().isAntiDupeEnabled())));

        metrics.addCustomChart(new Metrics.SingleLineChart("online_players", () ->
            plugin.getServer().getOnlinePlayers().size()));
    }

    public void shutdown() {
        metrics.shutdown();
    }
}
