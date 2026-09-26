package com.example.basilience;

import com.google.firebase.firestore.DocumentSnapshot;

/**
 * The parts of one parameterLogs document that a Parameter Report uses,
 * read once with the same tolerant readers the report always used.
 *
 * A malformed or missing field is null (FirebaseSafeRead logs it with the
 * document path), so that reading is left out of the report rather than
 * replaced by a made-up value. Holding these instead of the Firestore
 * document keeps a large report's memory to a few dozen bytes per reading.
 */
final class ParameterLogRecord {

    final Long timestamp;
    final Double ph;
    final Double ec;
    final Double airTemp;
    final Double humidity;
    final Double waterTemp;
    final Double waterLevel;

    ParameterLogRecord(Long timestamp, Double ph, Double ec, Double airTemp,
                       Double humidity, Double waterTemp, Double waterLevel) {
        this.timestamp = timestamp;
        this.ph = ph;
        this.ec = ec;
        this.airTemp = airTemp;
        this.humidity = humidity;
        this.waterTemp = waterTemp;
        this.waterLevel = waterLevel;
    }

    static ParameterLogRecord from(DocumentSnapshot doc) {
        return new ParameterLogRecord(
                FirebaseSafeRead.fsLong(doc, "timestamp"),
                FirebaseSafeRead.fsDouble(doc, "ph"),
                FirebaseSafeRead.fsDouble(doc, "ec"),
                FirebaseSafeRead.fsDouble(doc, "air_temp"),
                FirebaseSafeRead.fsDouble(doc, "humidity"),
                FirebaseSafeRead.fsDouble(doc, "water_temp"),
                FirebaseSafeRead.fsDouble(doc, "water_level"));
    }

    /** The value stored under one of the report's Firestore field names, or null for any other name. */
    Double valueOf(String dbFieldName) {
        if (dbFieldName == null) return null;
        switch (dbFieldName) {
            case "ph": return ph;
            case "ec": return ec;
            case "air_temp": return airTemp;
            case "humidity": return humidity;
            case "water_temp": return waterTemp;
            case "water_level": return waterLevel;
            default: return null;
        }
    }
}
