package com.itemguard.data;

public class PluginStats {

    private int totalItems;
    private int totalHistory;
    private int onlineTracked;
    private int duplicatesDetected;
    private int distinctDuplicateItems;
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

    /**
     * Number of distinct item identities behind {@link #getDuplicatesDetected()}. The same duplicated
     * item is re-detected on every scan epoch, so the raw detection count alone reads as that many
     * duplicated items.
     */
    public int getDistinctDuplicateItems() {
        return distinctDuplicateItems;
    }

    public void setDistinctDuplicateItems(int distinctDuplicateItems) {
        this.distinctDuplicateItems = distinctDuplicateItems;
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
