package com.example.basilience;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.IgnoreExtraProperties;

@IgnoreExtraProperties
public class Harvest {
    private String id;
    private Timestamp harvestDate;
    private double weight; // in grams
    private String recordedBy; // User UID
    private String recordedByName; // User Full Name
    private String source; // MANUAL or SENSOR
    private String notes;
    private Timestamp createdAt;

    public Harvest() {
        // Required for Firestore
    }

    public Harvest(Timestamp harvestDate, double weight, String recordedBy, String recordedByName, String source, String notes) {
        this.harvestDate = harvestDate;
        this.weight = weight;
        this.recordedBy = recordedBy;
        this.recordedByName = recordedByName;
        this.source = source;
        this.notes = notes;
        this.createdAt = Timestamp.now();
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Timestamp getTimestamp() { return harvestDate; }
    public void setTimestamp(Timestamp timestamp) { this.harvestDate = timestamp; }

    public Timestamp getHarvestDate() { return harvestDate; }
    public void setHarvestDate(Timestamp harvestDate) { this.harvestDate = harvestDate; }

    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }

    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String recordedBy) { this.recordedBy = recordedBy; }

    public String getRecordedByName() { return recordedByName; }
    public void setRecordedByName(String recordedByName) { this.recordedByName = recordedByName; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}
