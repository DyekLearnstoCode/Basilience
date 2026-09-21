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
    private String source; // MANUAL or SCALE
    private String notes;
    private Timestamp createdAt;

    // Provenance for a SCALE-sourced harvest only - traces this Harvest back
    // to the exact physical weighing that produced it (see
    // Database_Helper.addHarvestTransaction(String, Harvest,
    // HarvestScaleReading) and harvestScaleConsumptions/{id}, the permanent
    // record that this measurement has already been consumed). Null/absent
    // for MANUAL entries and for every Harvest recorded before this field
    // existed - @IgnoreExtraProperties plus these being plain nullable
    // Strings/long (not primitives) means older records deserialize fine
    // with these simply unset, and existing history/PDF/chart code (which
    // only ever reads weight/harvestDate/source/notes) is unaffected.
    private String scaleDeviceId;
    private String scaleMeasurementId;
    private Long scaleCapturedAt; // epoch seconds, boxed so "absent" (null) is distinguishable from 0/unsynced-clock

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

    public String getScaleDeviceId() { return scaleDeviceId; }
    public void setScaleDeviceId(String scaleDeviceId) { this.scaleDeviceId = scaleDeviceId; }

    public String getScaleMeasurementId() { return scaleMeasurementId; }
    public void setScaleMeasurementId(String scaleMeasurementId) { this.scaleMeasurementId = scaleMeasurementId; }

    public Long getScaleCapturedAt() { return scaleCapturedAt; }
    public void setScaleCapturedAt(Long scaleCapturedAt) { this.scaleCapturedAt = scaleCapturedAt; }
}
