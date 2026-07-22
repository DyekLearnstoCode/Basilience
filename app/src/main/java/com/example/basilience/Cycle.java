package com.example.basilience;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.IgnoreExtraProperties;

@IgnoreExtraProperties
public class Cycle {
    private String cycleId;
    private String cycleName;
    private int cycleNumber;
    private String status;
    private Timestamp startDate;
    private Timestamp expectedHarvestDate;
    private Timestamp endDate;
    private Timestamp lastHarvestDate;
    private Timestamp nextHarvestDate;
    private int harvestFrequencyDays;
    private String createdBy;
    private int totalHarvestCount;
    private double totalHarvestWeight;
    private String notes;
    private long createdAt;

    // Required for Firestore
    public Cycle() {}

    public Cycle(int cycleNumber, Timestamp startDate, String status) {
        this.cycleId = "cycle_" + System.currentTimeMillis();
        this.cycleName = "Cycle #" + cycleNumber;
        this.cycleNumber = cycleNumber;
        this.status = status;
        this.startDate = startDate;
        this.createdAt = System.currentTimeMillis();
    }

    // Getters and Setters
    public String getCycleId() { return cycleId; }
    public void setCycleId(String cycleId) { this.cycleId = cycleId; }

    public String getCycleName() { return cycleName; }
    public void setCycleName(String cycleName) { this.cycleName = cycleName; }

    public int getCycleNumber() { return cycleNumber; }
    public void setCycleNumber(int cycleNumber) { this.cycleNumber = cycleNumber; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Timestamp getStartDate() { return startDate; }
    public void setStartDate(Timestamp startDate) { this.startDate = startDate; }

    public Timestamp getExpectedHarvestDate() { return expectedHarvestDate; }
    public void setExpectedHarvestDate(Timestamp expectedHarvestDate) { this.expectedHarvestDate = expectedHarvestDate; }

    public Timestamp getEndDate() { return endDate; }
    public void setEndDate(Timestamp endDate) { this.endDate = endDate; }

    public Timestamp getLastHarvestDate() { return lastHarvestDate; }
    public void setLastHarvestDate(Timestamp lastHarvestDate) { this.lastHarvestDate = lastHarvestDate; }

    public Timestamp getNextHarvestDate() { return nextHarvestDate; }
    public void setNextHarvestDate(Timestamp nextHarvestDate) { this.nextHarvestDate = nextHarvestDate; }

    public int getHarvestFrequencyDays() { return harvestFrequencyDays; }
    public void setHarvestFrequencyDays(int harvestFrequencyDays) { this.harvestFrequencyDays = harvestFrequencyDays; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public int getTotalHarvestCount() { return totalHarvestCount; }
    public void setTotalHarvestCount(int totalHarvestCount) { this.totalHarvestCount = totalHarvestCount; }

    public double getTotalHarvestWeight() { return totalHarvestWeight; }
    public void setTotalHarvestWeight(double totalHarvestWeight) { this.totalHarvestWeight = totalHarvestWeight; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
