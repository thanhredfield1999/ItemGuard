package com.itemguard.data;

public class PluginStats {

    private int totalItems;
    private int totalHistory;
    private int onlineTracked;
    private int duplicatesDetected;
    private String databaseType;
    private String databaseStatus;

    public PluginStats() {
        this.databaseStatus = "OK";
    }

    public int getTotalItems() {
        return totalItems;
    }

    public void setTotalItems(int totalItems) {
        this.totalItems = totalItems;
    }

    public int getTotalHistory() {
        return totalHistory;
    }

    public void setTotalHistory(int totalHistory) {
        this.totalHistory = totalHistory;
    }

    public int getOnlineTracked() {
        return onlineTracked;
    }

    public void setOnlineTracked(int onlineTracked) {
        this.onlineTracked = onlineTracked;
    }

    public int getDuplicatesDetected() {
        return duplicatesDetected;
    }

    public void setDuplicatesDetected(int duplicatesDetected) {
        this.duplicatesDetected = duplicatesDetected;
    }

    public String getDatabaseType() {
        return databaseType;
    }

    public void setDatabaseType(String databaseType) {
        this.databaseType = databaseType;
    }

    public String getDatabaseStatus() {
        return databaseStatus;
    }

    public void setDatabaseStatus(String databaseStatus) {
        this.databaseStatus = databaseStatus;
    }
}
