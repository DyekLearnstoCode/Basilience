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
    // Set only when the cycle is completed. Historical cycles completed before
    // this field existed simply leave it null and deserialize normally.
    private String completedBy;

    // The parameter target ranges in force when this cycle was created, keyed by
    // the canonical setting names (minPH, maxAirTemp, ...). Reporting metadata
    // only - the device and Monitoring always run on current settings.
    //
    // Deliberately Map<String, Object> rather than Map<String, Double>: Firestore
    // can hand a whole number back as a Long, and a stricter declaration would
    // make the whole document fail to deserialize. Legacy cycles created before
    // this existed simply leave it null.
    private java.util.Map<String, Object> targetRanges;
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

    public String getCompletedBy() { return completedBy; }
    public void setCompletedBy(String completedBy) { this.completedBy = completedBy; }

    public java.util.Map<String, Object> getTargetRanges() { return targetRanges; }
    public void setTargetRanges(java.util.Map<String, Object> targetRanges) { this.targetRanges = targetRanges; }

    public int getTotalHarvestCount() { return totalHarvestCount; }
    public void setTotalHarvestCount(int totalHarvestCount) { this.totalHarvestCount = totalHarvestCount; }

    public double getTotalHarvestWeight() { return totalHarvestWeight; }
    public void setTotalHarvestWeight(double totalHarvestWeight) { this.totalHarvestWeight = totalHarvestWeight; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
